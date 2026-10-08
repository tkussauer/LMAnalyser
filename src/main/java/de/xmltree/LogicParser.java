package de.xmltree;

import org.w3c.dom.Comment;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Übersetzt die Regel-Elemente eines Bausteins ({@code set}, {@code conditions},
 * {@code iteration}, …) in eine lesbare Struktur aus {@link LogicNode}s.
 */
final class LogicParser {

    private static final Map<String, String> OPERATORS = Map.ofEntries(
            Map.entry("equalto", "="),
            Map.entry("notequalto", "≠"),
            Map.entry("greaterthan", ">"),
            Map.entry("greaterthanorequalto", "≥"),
            Map.entry("greaterorequal", "≥"),
            Map.entry("lessthan", "<"),
            Map.entry("lessthanorequalto", "≤"),
            Map.entry("lessorequal", "≤"),
            Map.entry("and", "UND"),
            Map.entry("or", "ODER"),
            Map.entry("not", "NICHT"),
            Map.entry("contains", "enthält"),
            Map.entry("startswith", "beginnt mit"),
            Map.entry("endswith", "endet mit"),
            Map.entry("plus", "+"),
            Map.entry("minus", "−"));

    /** {@code ＜textruleobject callId=110 description=… executionStrategy=…＞} (auch mit normalen spitzen Klammern). */
    private static final Pattern RULE_COMMENT = Pattern.compile(
            "[＜<]\\s*(text(?:multiple)?ruleobject)\\s+(.*?)[＞>]?\\s*$", Pattern.DOTALL);
    private static final Pattern RULE_ATTRIBUTE = Pattern.compile("(\\w+)=(.*?)(?=\\s+\\w+=|$)", Pattern.DOTALL);
    private static final Pattern CALL_MARKER = Pattern.compile("(?i)\\s*(END\\s+)?call\\s+\\d+\\s*");

    private LogicParser() {
    }

    /** Liest die Logik unterhalb des Root-Elements. */
    static List<LogicNode> parse(Element root) {
        return children(root);
    }

    private static List<LogicNode> children(Node parent) {
        List<LogicNode> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                LogicNode parsed = element(element);
                if (parsed != null) {
                    result.add(parsed);
                }
            } else if (node instanceof Comment comment) {
                LogicNode label = comment(comment.getData());
                if (label != null) {
                    result.add(label);
                }
            } else if (node instanceof Text text && !text.getData().isBlank()) {
                result.add(LogicNode.leaf(LogicNode.Kind.TEXT, normalize(text.getData()), null));
            }
        }
        return result;
    }

    private static LogicNode element(Element element) {
        String tag = local(element.getTagName());
        switch (tag) {
            case "set": {
                List<Element> parts = elements(element);
                if (parts.size() >= 2 && OPERATORS.containsKey(local(parts.get(1).getTagName()))) {
                    String target = expression(parts.get(0));
                    String value = sequence(parts.subList(2, parts.size()));
                    return LogicNode.leaf(LogicNode.Kind.SET, target, value.isEmpty() ? "\"\"" : value);
                }
                return LogicNode.leaf(LogicNode.Kind.SET, sequence(parts), null);
            }
            case "conditions": {
                List<Element> condition = new ArrayList<>();
                List<LogicNode> body = new ArrayList<>();
                for (Element child : elements(element)) {
                    String childTag = local(child.getTagName());
                    if (childTag.equals("then")) {
                        body.addAll(children(child));
                    } else if (childTag.equals("else")) {
                        body.add(new LogicNode(LogicNode.Kind.ELSE, "SONST", null, children(child)));
                    } else {
                        condition.add(child);
                    }
                }
                return new LogicNode(LogicNode.Kind.IF, sequence(condition), null, body);
            }
            case "iteration": {
                String description = element.getAttribute("description");
                return new LogicNode(LogicNode.Kind.ITERATION, element.getAttribute("name"),
                        description.isBlank() || description.equalsIgnoreCase("repeat") ? null : description,
                        children(element));
            }
            case "textblock": {
                String id = element.getAttribute("textblockID");
                if (!id.isBlank()) {
                    return LogicNode.leaf(LogicNode.Kind.REF, id.trim(), null);
                }
                break;
            }
            case "group":
                return new LogicNode(LogicNode.Kind.GROUP, element.getAttribute("description"), null, children(element));
            case "groupentry": {
                List<String> flags = new ArrayList<>();
                if ("true".equalsIgnoreCase(element.getAttribute("selected"))) {
                    flags.add("vorausgewählt");
                }
                if ("true".equalsIgnoreCase(element.getAttribute("optional"))) {
                    flags.add("optional");
                }
                String description = element.getAttribute("description");
                String detail = description + (flags.isEmpty() ? "" : " (" + String.join(", ", flags) + ")");
                return new LogicNode(LogicNode.Kind.ENTRY, element.getAttribute("name"),
                        detail.isBlank() ? null : detail.trim(), children(element));
            }
            default:
                break;
        }
        List<LogicNode> children = children(element);
        String title = "<" + element.getTagName() + attributes(element) + ">";
        if (children.size() == 1 && children.get(0).kind() == LogicNode.Kind.TEXT) {
            return LogicNode.leaf(LogicNode.Kind.ELEMENT, title, children.get(0).title());
        }
        return new LogicNode(LogicNode.Kind.ELEMENT, title, null, children);
    }

    /** Regel-Kommentare werden zu Überschriften, „call 110“/„END call 110“ entfallen. */
    static LogicNode comment(String data) {
        if (CALL_MARKER.matcher(data).matches()) {
            return null;
        }
        Matcher rule = RULE_COMMENT.matcher(data.trim());
        if (rule.matches()) {
            String callId = null;
            String description = null;
            String reference = null;
            Matcher attribute = RULE_ATTRIBUTE.matcher(rule.group(2).trim());
            while (attribute.find()) {
                String value = attribute.group(2).trim();
                switch (attribute.group(1).toLowerCase(Locale.ROOT)) {
                    case "callid" -> callId = value;
                    case "description" -> description = value;
                    case "tforeference" -> reference = value;
                    default -> { }
                }
            }
            String kind = rule.group(1).toLowerCase(Locale.ROOT).contains("multiple") ? "Regelgruppe" : "Regel";
            String title = kind + (callId != null ? " " + callId : "") + (description != null ? " · " + description : "");
            return LogicNode.leaf(LogicNode.Kind.LABEL, title, reference != null ? "für " + reference : null);
        }
        String text = normalize(data);
        return text.isEmpty() ? null : LogicNode.leaf(LogicNode.Kind.LABEL, text, null);
    }

    /** Formatiert ein Ausdruckselement, z. B. eine Variable, einen Wert oder eine Funktion. */
    static String expression(Element element) {
        String tag = local(element.getTagName());
        String operator = OPERATORS.get(tag);
        if (operator != null && elements(element).isEmpty()) {
            return operator;
        }
        switch (tag) {
            case "variable": {
                String name = element.getAttribute("name");
                return name.isBlank() ? element.getAttribute("uname") : name;
            }
            case "conditionvalue":
                return "\"" + normalize(element.getTextContent()) + "\"";
            case "const":
                return normalize(element.getTextContent());
            case "function":
                return element.getAttribute("name") + "(" + String.join(", ",
                        elements(element).stream().map(LogicParser::expression).toList()) + ")";
            case "cmd": {
                String orig = element.getAttribute("orig");
                String body = orig.isBlank() ? sequence(elements(element)) : orig.replace("&", "");
                return element.getAttribute("name") + "(" + normalize(body) + ")";
            }
            case "parenthesis":
                return "(" + sequence(elements(element)) + ")";
            case "condition":
                return sequence(elements(element));
            default: {
                List<Element> children = elements(element);
                if (children.isEmpty()) {
                    String text = normalize(element.getTextContent());
                    return text.isEmpty() ? element.getTagName() : element.getTagName() + "(" + text + ")";
                }
                return element.getTagName() + "(" + sequence(children) + ")";
            }
        }
    }

    private static String sequence(List<Element> elements) {
        return String.join(" ", elements.stream().map(LogicParser::expression).toList());
    }

    private static List<Element> elements(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child) {
                result.add(child);
            }
        }
        return result;
    }

    private static String attributes(Element element) {
        StringBuilder out = new StringBuilder();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attr = attributes.item(i);
            out.append(' ').append(attr.getNodeName()).append("=\"").append(attr.getNodeValue()).append('"');
        }
        return out.toString();
    }

    private static String local(String name) {
        int colon = name.indexOf(':');
        return (colon >= 0 ? name.substring(colon + 1) : name).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
