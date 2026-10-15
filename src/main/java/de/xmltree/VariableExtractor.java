package de.xmltree;

import org.w3c.dom.Attr;
import org.w3c.dom.Comment;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sucht in einem Baustein alle Variablen:
 * <ul>
 *   <li>{@code <variable name="…" uname="…" iterkey="…" datatype="…" automatic="…"/>}</li>
 *   <li>Zuordnungen {@code name,uname}, z. B. {@code dokument.fachdaten.berufBezug,Data.BusinessData.berufBezug}</li>
 *   <li>Datenpfade mit Punkt, z. B. {@code system_BFSLeben.dokumentDaten.Doku.Dynamik}</li>
 *   <li>{@code VAR_…}-Namen, auch als {@code &VAR_…}</li>
 *   <li>Namenslisten, z. B. {@code varLeBerater, varLeBeratungsdatum} oder {@code Anzahl_Fonds}</li>
 * </ul>
 * Werte in {@code <conditionValue>} und {@code <const>} sind Literale, {@code <meta>} sind
 * Verwaltungsdaten – beides wird übersprungen.
 */
final class VariableExtractor {

    /** Ein Bezeichner, wie er in Variablennamen vorkommt (Punkte und Bindestriche erlaubt). */
    private static final String IDENT = "[A-Za-z_][\\w.-]*";
    private static final Pattern MAPPING = Pattern.compile("\\s*\"?(" + IDENT + ")\"?\\s*,\\s*\"?(" + IDENT + ")\"?\\s*");
    private static final Pattern PATH = Pattern.compile("(?<![\\w@./-])([A-Za-z_][\\w-]*(?:\\.[A-Za-z_][\\w-]*)+)");
    private static final Pattern VAR_NAME = Pattern.compile("(?<![\\w.])(VAR_[A-Za-z0-9_]+)");
    private static final Pattern NAME = Pattern.compile(IDENT);
    private static final Pattern CAMEL = Pattern.compile("[a-z][A-Z]");
    /** Endungen, die auf Dateinamen, Domains o. Ä. statt auf Datenpfade hindeuten. */
    private static final Set<String> NOT_A_PATH_SUFFIX = Set.of(
            "cml", "xml", "xsd", "pdf", "html", "htm", "doc", "docx", "jpg", "jpeg", "png", "gif", "tif", "tiff",
            "de", "com", "net", "org", "eu", "info");
    /** Attribute, deren Werte Beschriftungen/Steuerwerte und keine Variablen sind. */
    private static final Set<String> SKIPPED_ATTRIBUTES = Set.of(
            "description", "selected", "optional", "textblockid", "id", "datatype", "iterkey", "automatic", "uname");

    private final DocumentBuilder builder = XmlScanner.newSecureBuilder();

    List<VariableRef> extract(Path file) throws IOException, SAXException {
        Element root = builder.parse(file.toFile()).getDocumentElement();
        builder.reset();
        Set<VariableRef> found = new LinkedHashSet<>();
        visitChildren(root, found);
        return new ArrayList<>(found);
    }

    /** Das Root-Element selbst (ID, Description, Source_ID, …) enthält keine Variablen. */
    private void visitChildren(Element element, Set<VariableRef> out) {
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element child) {
                visit(child, out);
            } else if (node instanceof Text text) {
                scanText(text.getData(), "<" + element.getTagName() + ">", true, out);
            } else if (node instanceof Comment comment) {
                // Kommentare nur auf eindeutige Muster prüfen (z. B. tforeference=system_….)
                scanText(comment.getData(), "Kommentar", false, out);
            }
        }
    }

    private void visit(Element element, Set<VariableRef> out) {
        String tag = ScanOptions.localName(element.getTagName());
        switch (tag) {
            case "meta", "conditionvalue", "const" -> {
                return;
            }
            case "variable" -> {
                String name = element.getAttribute("name").trim();
                String uname = element.getAttribute("uname").trim();
                out.add(new VariableRef(name.isEmpty() ? uname : name, uname,
                        element.getAttribute("iterkey").trim(), element.getAttribute("datatype").trim(),
                        element.getAttribute("automatic").trim(), VariableRef.Kind.VARIABLE_ELEMENT, "<variable>"));
                return;
            }
            default -> { }
        }
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);
            String attrName = ScanOptions.localName(attr.getName());
            boolean label = attrName.equals("name") && !tag.equals("iteration");
            if (SKIPPED_ATTRIBUTES.contains(attrName) || label || attr.getName().startsWith("xmlns")) {
                continue;
            }
            scanText(attr.getValue(), element.getTagName() + "/@" + attr.getName(), true, out);
        }
        visitChildren(element, out);
    }

    /**
     * @param wholeValue ob der Text als Ganzes ausgewertet werden darf (Zuordnung, Namensliste) –
     *                   bei Kommentaren nur Pfade und VAR-Namen
     */
    static void scanText(String text, String location, boolean wholeValue, Set<VariableRef> out) {
        if (text == null || text.isBlank()) {
            return;
        }
        String value = text.trim();
        if (wholeValue) {
            Matcher mapping = MAPPING.matcher(value);
            if (mapping.matches() && (mapping.group(1).contains(".") || mapping.group(2).contains("."))) {
                out.add(ref(mapping.group(1), mapping.group(2), VariableRef.Kind.MAPPING, location));
                return;
            }
            List<String> names = nameList(value);
            if (!names.isEmpty()) {
                for (String name : names) {
                    VariableRef.Kind kind = name.startsWith("VAR_") ? VariableRef.Kind.VAR_NAME
                            : name.contains(".") ? VariableRef.Kind.PATH : VariableRef.Kind.NAME_LIST;
                    out.add(ref(name, "", kind, location));
                }
                return;
            }
        }
        Matcher path = PATH.matcher(value);
        while (path.find()) {
            String candidate = path.group(1);
            if (looksLikePath(candidate)) {
                out.add(ref(candidate, "", VariableRef.Kind.PATH, location));
            }
        }
        Matcher var = VAR_NAME.matcher(value);
        while (var.find()) {
            out.add(ref(var.group(1), "", VariableRef.Kind.VAR_NAME, location));
        }
    }

    /**
     * Wert, der nur aus durch Komma getrennten Variablennamen besteht, z. B. {@code vermittlerNr, VAR_KLAUSEL}.
     * Jeder Name muss nach Variable aussehen (Punkt, Unterstrich oder camelCase), damit normale Wörter
     * wie „Werkstatt“ nicht erfasst werden.
     */
    private static List<String> nameList(String value) {
        List<String> names = new ArrayList<>();
        for (String part : value.split(",")) {
            String name = part.trim().replace("\"", "");
            if (!NAME.matcher(name).matches() || !variableLike(name)) {
                return List.of();
            }
            if (name.contains(".") && !looksLikePath(name)) {
                return List.of();
            }
            names.add(name);
        }
        return names;
    }

    private static boolean variableLike(String name) {
        return name.contains(".") || name.contains("_") || CAMEL.matcher(name).find();
    }

    /** Filtert Abkürzungen („z.B“), Dateinamen und Domains heraus. */
    private static boolean looksLikePath(String candidate) {
        String[] segments = candidate.split("\\.");
        if (segments.length < 2 || segments[0].equalsIgnoreCase("www")) {
            return false;
        }
        for (String segment : segments) {
            if (segment.length() < 2) {
                return false;
            }
        }
        return !NOT_A_PATH_SUFFIX.contains(segments[segments.length - 1].toLowerCase(Locale.ROOT));
    }

    private static VariableRef ref(String name, String uname, VariableRef.Kind kind, String location) {
        return new VariableRef(name, uname, "", "", "", kind, location);
    }
}
