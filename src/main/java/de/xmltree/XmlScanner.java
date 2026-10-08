package de.xmltree;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Liest XML-Dateien ein und ermittelt ID, Description und Referenzkandidaten. */
public class XmlScanner {

    private final ScanOptions options;
    private final DocumentBuilder builder;
    private final List<String> warnings = new ArrayList<>();

    public XmlScanner(ScanOptions options) {
        this.options = options;
        this.builder = newSecureBuilder();
    }

    public List<String> warnings() {
        return warnings;
    }

    /** Liest alle Dateien mit den konfigurierten Endungen (Standard *.cml) unterhalb der angegebenen Verzeichnisse (rekursiv) ein. */
    public List<XmlFile> scanDirectories(List<Path> directories) {
        List<XmlFile> result = new ArrayList<>();
        for (Path dir : directories) {
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> options.hasExtension(p.getFileName().toString()))
                        .sorted()
                        .forEach(p -> {
                            try {
                                result.add(scanFile(p));
                            } catch (IOException | SAXException e) {
                                warnings.add("Datei übersprungen (" + p + "): " + e.getMessage());
                            }
                        });
            } catch (IOException e) {
                throw new UncheckedIOException("Verzeichnis nicht lesbar: " + dir, e);
            }
        }
        return result;
    }

    public XmlFile scanFile(Path path) throws IOException, SAXException {
        Document doc = builder.parse(path.toFile());
        builder.reset();
        Element root = doc.getDocumentElement();

        String id = null;
        String description = null;
        List<Node> ownKeyNodes = new ArrayList<>();

        NamedNodeMap attributes = root.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);
            if (id == null && options.isId(attr.getName())) {
                id = attr.getValue().trim();
                ownKeyNodes.add(attr);
            } else if (description == null && options.isDescription(attr.getName())) {
                description = attr.getValue().trim();
                ownKeyNodes.add(attr);
            }
        }
        // Alternativ: ID/Description als direkte Kindelemente des Root-Elements
        for (Element child : childElements(root)) {
            if (id == null && options.isId(child.getTagName()) && isTextOnly(child)) {
                id = child.getTextContent().trim();
                ownKeyNodes.add(child);
            } else if (description == null && options.isDescription(child.getTagName()) && isTextOnly(child)) {
                description = child.getTextContent().trim();
                ownKeyNodes.add(child);
            }
        }

        List<XmlFile.Candidate> candidates = new ArrayList<>();
        collectCandidates(root, "", ownKeyNodes, candidates);
        return new XmlFile(path, root.getTagName(), emptyToNull(id), emptyToNull(description), candidates,
                LogicParser.parse(root));
    }

    /**
     * @param context Pfad der beschrifteten Vorfahren (z. B. Gruppen), damit im Baum sichtbar ist,
     *                in welcher Gruppe ein Verweis steht
     */
    private void collectCandidates(Element element, String context, List<Node> ownKeyNodes,
                                   List<XmlFile.Candidate> out) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);
            if (ownKeyNodes.contains(attr) || attr.getName().startsWith("xmlns")) {
                continue;
            }
            if (!options.restrictsReferences() || options.isReference(attr.getName())) {
                addCandidate(attr.getValue(), context + element.getTagName() + "/@" + attr.getName(), out);
            }
        }
        List<Element> children = childElements(element);
        if (children.isEmpty() && !ownKeyNodes.contains(element)
                && (!options.restrictsReferences() || options.isReference(element.getTagName()))) {
            addCandidate(element.getTextContent(), context + "<" + element.getTagName() + ">", out);
        }
        String childContext = context;
        if (element.getParentNode() != element.getOwnerDocument()) {
            String label = label(element);
            if (label != null) {
                childContext = context + label + " › ";
            }
        }
        for (Element child : children) {
            collectCandidates(child, childContext, ownKeyNodes, out);
        }
    }

    /** Beschriftung eines Strukturelements, z. B. {@code <group description="…">} oder {@code name="…"}. */
    private static String label(Element element) {
        for (String attribute : List.of("description", "name")) {
            String value = element.getAttribute(attribute).trim();
            if (!value.isEmpty()) {
                return element.getTagName() + " „" + value + "“";
            }
        }
        return null;
    }

    private static void addCandidate(String value, String source, List<XmlFile.Candidate> out) {
        if (value != null && !value.isBlank()) {
            out.add(new XmlFile.Candidate(value.trim(), source));
        }
    }

    private static List<Element> childElements(Element element) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child) {
                result.add(child);
            }
        }
        return result;
    }

    private static boolean isTextOnly(Element element) {
        return childElements(element).isEmpty();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Parser ohne DTD-/External-Entity-Auflösung (Schutz vor XXE). */
    private static DocumentBuilder newSecureBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder documentBuilder = factory.newDocumentBuilder();
            documentBuilder.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override
                public void warning(org.xml.sax.SAXParseException e) {
                }

                @Override
                public void error(org.xml.sax.SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return documentBuilder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }
}
