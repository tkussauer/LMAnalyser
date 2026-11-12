package de.xmltree;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Liest eine Word-Datei (docx/dotx/docm/dotm) und ermittelt je Bereich – Inhalt, Kopfzeile,
 * Fußzeile – alle Felder und Bedingungen.
 * <ul>
 *   <li>Feld: jedes Word-Feld ({@code MERGEFIELD}, {@code DATE}, {@code PAGE}, …) und jedes
 *       Inhaltssteuerelement</li>
 *   <li>Bedingung: die Felder {@code IF}, {@code COMPARE}, {@code SKIPIF} und {@code NEXTIF}</li>
 * </ul>
 * Felder in einer Bedingung (z. B. {@code IF { MERGEFIELD X } = "1" …}) zählen zusätzlich als Feld.
 */
final class DocxAnalyzer {

    enum Area {
        INHALT("Inhalt"), KOPFZEILE("Kopfzeile"), FUSSZEILE("Fußzeile");

        final String label;

        Area(String label) {
            this.label = label;
        }
    }

    /**
     * Ein Feld oder eine Bedingung.
     *
     * @param area      Bereich
     * @param part      Teil im Dokument, z. B. {@code footer2.xml (Standard)}
     * @param condition {@code true} für Bedingungen
     * @param type      Feldart, z. B. MERGEFIELD, IF, DATE, Inhaltssteuerelement
     * @param name      Feldname (MERGEFIELD), Feldcode (Bedingung) oder Titel/Tag
     */
    record Item(Area area, String part, boolean condition, String type, String name) {
    }

    private static final Set<String> CONDITIONS = Set.of("IF", "COMPARE", "SKIPIF", "NEXTIF");
    private static final Pattern REL = Pattern.compile("<Relationship\\b[^>]*>");
    private static final Pattern ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Map<String, String> REFERENCE_TYPES = Map.of(
            "default", "Standard", "first", "erste Seite", "even", "gerade Seiten");

    private final DocumentBuilder builder = XmlScanner.newSecureBuilder();

    List<Item> analyze(Path file) throws IOException, SAXException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            String mainPart = mainDocumentPart(zip);
            String dir = mainPart.contains("/") ? mainPart.substring(0, mainPart.lastIndexOf('/') + 1) : "";
            Element document = parse(zip, mainPart);
            if (document == null) {
                throw new IOException("Kein Word-Dokument (" + mainPart + " fehlt)");
            }

            // Kopf-/Fußzeilen über die Beziehungen und die Abschnittsangaben (erste Seite, gerade, Standard)
            Map<String, String> targets = relationships(zip, dir + "_rels/" + mainPart.substring(dir.length()) + ".rels");
            Map<String, Set<String>> usage = new LinkedHashMap<>();
            for (String kind : List.of("headerReference", "footerReference")) {
                NodeList refs = document.getElementsByTagName("w:" + kind);
                for (int i = 0; i < refs.getLength(); i++) {
                    Element ref = (Element) refs.item(i);
                    String target = targets.get(ref.getAttribute("r:id"));
                    if (target != null) {
                        String type = ref.getAttribute("w:type");
                        usage.computeIfAbsent(target, t -> new LinkedHashSet<>())
                                .add(REFERENCE_TYPES.getOrDefault(type, type.isEmpty() ? "Standard" : type));
                    }
                }
            }

            List<Item> items = new ArrayList<>();
            collect(document, Area.INHALT, mainPart.substring(dir.length()), items);
            for (String extra : List.of("footnotes.xml", "endnotes.xml")) {
                Element part = parse(zip, dir + extra);
                if (part != null) {
                    collect(part, Area.INHALT, extra, items);
                }
            }
            // alle Kopf-/Fußzeilen, auch nicht referenzierte, in fester Reihenfolge
            List<String> headerFooter = new ArrayList<>(new LinkedHashSet<>(targets.values()));
            headerFooter.removeIf(t -> !t.matches("(?i).*(header|footer)\\d*\\.xml"));
            headerFooter.sort(String.CASE_INSENSITIVE_ORDER);
            for (String target : headerFooter) {
                Element part = parse(zip, dir + target);
                if (part == null) {
                    continue;
                }
                Area area = target.toLowerCase(Locale.ROOT).contains("header") ? Area.KOPFZEILE : Area.FUSSZEILE;
                Set<String> types = usage.get(target);
                String label = target + (types == null ? " (nicht verwendet)" : " (" + String.join(", ", types) + ")");
                collect(part, area, label, items);
            }
            return items;
        }
    }

    /** Hauptdokument laut [Content_Types].xml bzw. _rels/.rels, sonst word/document.xml. */
    private static String mainDocumentPart(ZipFile zip) throws IOException {
        String rels = read(zip, "_rels/.rels");
        if (rels != null) {
            Matcher m = REL.matcher(rels);
            while (m.find()) {
                Map<String, String> attrs = attributes(m.group());
                if (attrs.getOrDefault("Type", "").endsWith("/officeDocument")) {
                    String target = attrs.get("Target");
                    return target.startsWith("/") ? target.substring(1) : target;
                }
            }
        }
        return "word/document.xml";
    }

    private static Map<String, String> relationships(ZipFile zip, String relsPath) throws IOException {
        Map<String, String> result = new HashMap<>();
        String rels = read(zip, relsPath);
        if (rels == null) {
            return result;
        }
        Matcher m = REL.matcher(rels);
        while (m.find()) {
            Map<String, String> attrs = attributes(m.group());
            String type = attrs.getOrDefault("Type", "");
            if (type.endsWith("/header") || type.endsWith("/footer")) {
                result.put(attrs.get("Id"), attrs.get("Target"));
            }
        }
        return result;
    }

    private static Map<String, String> attributes(String tag) {
        Map<String, String> attrs = new HashMap<>();
        Matcher a = ATTR.matcher(tag);
        while (a.find()) {
            attrs.put(a.group(1), a.group(2));
        }
        return attrs;
    }

    /**
     * Sammelt Felder in Dokumentreihenfolge. Komplexe Felder bestehen aus fldChar begin … instrText …
     * separate … end und können verschachtelt sein; einfache Felder sind w:fldSimple.
     */
    private static void collect(Element root, Area area, String part, List<Item> out) {
        Deque<StringBuilder> open = new ArrayDeque<>();
        Deque<Boolean> inCode = new ArrayDeque<>();
        walk(root, area, part, out, open, inCode);
    }

    private static void walk(Node node, Area area, String part, List<Item> out,
                             Deque<StringBuilder> open, Deque<Boolean> inCode) {
        if (node instanceof Element element) {
            switch (element.getTagName()) {
                case "w:fldChar" -> {
                    switch (element.getAttribute("w:fldCharType")) {
                        case "begin" -> {
                            open.push(new StringBuilder());
                            inCode.push(true);
                        }
                        case "separate" -> {
                            if (!inCode.isEmpty()) {
                                inCode.pop();
                                inCode.push(false);
                            }
                        }
                        case "end" -> {
                            if (!open.isEmpty()) {
                                inCode.pop();
                                add(open.pop().toString(), area, part, out);
                            }
                        }
                        default -> { }
                    }
                    return;
                }
                case "w:instrText" -> {
                    if (!open.isEmpty() && Boolean.TRUE.equals(inCode.peek())) {
                        open.peek().append(element.getTextContent());
                    }
                    return;
                }
                case "w:fldSimple" -> add(element.getAttribute("w:instr"), area, part, out);
                case "w:sdt" -> out.add(new Item(area, part, false, "Inhaltssteuerelement", contentControlName(element)));
                default -> { }
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            walk(child, area, part, out, open, inCode);
        }
    }

    private static void add(String code, Area area, String part, List<Item> out) {
        String text = code.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return;
        }
        String[] tokens = text.split(" ");
        String type = tokens[0].toUpperCase(Locale.ROOT);
        boolean condition = CONDITIONS.contains(type);
        String name;
        if (condition) {
            name = text;
        } else if (type.equals("MERGEFIELD") && tokens.length > 1) {
            name = tokens[1].replace("\"", "");
        } else {
            name = text;
        }
        out.add(new Item(area, part, condition, type, name));
    }

    private static String contentControlName(Element sdt) {
        for (String tag : List.of("w:alias", "w:tag")) {
            NodeList nodes = sdt.getElementsByTagName(tag);
            if (nodes.getLength() > 0) {
                String value = ((Element) nodes.item(0)).getAttribute("w:val");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private Element parse(ZipFile zip, String name) throws IOException, SAXException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            Element root = builder.parse(new InputSource(new ByteArrayInputStream(in.readAllBytes()))).getDocumentElement();
            builder.reset();
            return root;
        }
    }

    private static String read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
