package de.xmltree;

import org.w3c.dom.Comment;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Übersetzt die {@code <conditions>} eines Bausteins in lesbaren Skript-Code (JavaScript-Syntax).
 * <ul>
 *   <li><b>Variablen-Regeln:</b> Bedingungen, deren {@code <then>} Variablen per {@code <set>} setzt –
 *       je Variable ein {@code if … else if … else} mit {@code return}.</li>
 *   <li><b>Anzeige-Regeln:</b> Bedingungen, deren {@code <then>} Inhalt enthält (DCF-Content wie
 *       Tabellen und Absätze, eingebundene Bausteine) – als {@code display(…)} bzw. {@code include(…)}.</li>
 * </ul>
 * Aufeinanderfolgende {@code <conditions>} bilden eine Kette; ein Block mit dem Kommentar
 * „ELSE CONDITION“ oder ein {@code <else>} wird zum {@code else}. Verschachtelte Bedingungen
 * werden als umschließendes {@code if} ausgegeben.
 */
final class ScriptGenerator {

    /** Die Regeln eines Bausteins. */
    record Rules(List<String> variableRules, List<String> displayRules) {
        boolean isEmpty() {
            return variableRules.isEmpty() && displayRules.isEmpty();
        }
    }

    private static final Map<String, String> OPERATORS = Map.ofEntries(
            Map.entry("equalto", "=="), Map.entry("notequalto", "!="),
            Map.entry("greaterthan", ">"), Map.entry("greaterthanorequalto", ">="), Map.entry("greaterorequal", ">="),
            Map.entry("lessthan", "<"), Map.entry("lessthanorequalto", "<="), Map.entry("lessorequal", "<="),
            Map.entry("and", "&&"), Map.entry("or", "||"), Map.entry("not", "!"),
            Map.entry("plus", "+"), Map.entry("minus", "-"));
    private static final String INDENT = "    ";

    private final DocumentBuilder builder = XmlScanner.newSecureBuilder();
    private final Function<String, List<XmlFile>> lookup;

    /** @param lookup findet zu einer textblockID den Baustein, um bei include(…) die Description anzuzeigen */
    ScriptGenerator(Function<String, List<XmlFile>> lookup) {
        this.lookup = lookup;
    }

    Rules generate(Path file) throws IOException, SAXException {
        Element root = builder.parse(file.toFile()).getDocumentElement();
        builder.reset();
        Rules rules = new Rules(new ArrayList<>(), new ArrayList<>());
        process(root, null, null, rules);
        return rules;
    }

    /** Ein Zweig einer Kette: Bedingung ({@code null} = else) und der {@code <then>}-Inhalt. */
    private record Branch(String condition, Element body) {
    }

    /**
     * Durchläuft die Kinder eines Elements; aufeinanderfolgende {@code <conditions>} werden als Kette
     * verarbeitet, andere Elemente (Gruppen, Schleifen, …) nach weiteren Bedingungen durchsucht.
     *
     * @param context  umschließende Bedingung, {@code null} auf oberster Ebene
     * @param loop     umschließende Schleife ({@code <iteration name>}), sonst {@code null}
     */
    private void process(Element parent, String context, String loop, Rules out) {
        List<Element> chain = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element element)) {
                continue;
            }
            String tag = local(element);
            if (tag.equals("conditions")) {
                chain.add(element);
                continue;
            }
            flush(chain, context, loop, out);
            switch (tag) {
                case "meta", "set", "variable" -> { }
                case "iteration" -> process(element, context, element.getAttribute("name"), out);
                default -> process(element, context, loop, out);
            }
        }
        flush(chain, context, loop, out);
    }

    private void flush(List<Element> chain, String context, String loop, Rules out) {
        if (chain.isEmpty()) {
            return;
        }
        List<Branch> branches = new ArrayList<>();
        for (Element conditions : chain) {
            Element then = child(conditions, "then");
            Element otherwise = child(conditions, "else");
            boolean elseBlock = isElseBlock(conditions) && !branches.isEmpty();
            branches.add(new Branch(elseBlock ? null : condition(conditions), then));
            if (otherwise != null) {
                branches.add(new Branch(null, otherwise));
            }
        }
        chain.clear();
        // Ein else beendet eine Kette; danach beginnt eine neue (z. B. if/else, if/else)
        List<Branch> current = new ArrayList<>();
        for (Branch branch : branches) {
            current.add(branch);
            if (branch.condition() == null) {
                emitChain(current, context, loop, out);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            emitChain(current, context, loop, out);
        }
    }

    private void emitChain(List<Branch> branches, String context, String loop, Rules out) {
        // Variablen-Regeln: je Variable, die in einem Zweig gesetzt wird, eine eigene if-Kette
        Map<String, Map<Integer, String>> assignments = new LinkedHashMap<>();
        Map<String, String> descriptions = new LinkedHashMap<>();
        List<List<String>> content = new ArrayList<>();
        for (int i = 0; i < branches.size(); i++) {
            Element body = branches.get(i).body();
            List<String> shown = new ArrayList<>();
            if (body != null) {
                for (Element element : elements(body)) {
                    String tag = local(element);
                    if (tag.equals("set")) {
                        List<Element> parts = elements(element);
                        if (parts.isEmpty()) {
                            continue;
                        }
                        String variable = variableName(parts.get(0));
                        int valueStart = parts.size() > 1 && OPERATORS.containsKey(local(parts.get(1))) ? 2 : 1;
                        String value = sequence(parts.subList(valueStart, parts.size()));
                        assignments.computeIfAbsent(variable, v -> new LinkedHashMap<>()).put(i, value.isEmpty() ? "\"\"" : value);
                        String description = parts.get(0).getAttribute("description").trim();
                        if (!description.isEmpty()) {
                            descriptions.putIfAbsent(variable, description);
                        }
                    } else if (!tag.equals("conditions") && !tag.equals("meta")) {
                        shown.addAll(display(element));
                    }
                }
            }
            content.add(shown);
        }
        for (Map.Entry<String, Map<Integer, String>> variable : assignments.entrySet()) {
            StringBuilder code = new StringBuilder();
            code.append("// Variable ").append(variable.getKey());
            String description = descriptions.get(variable.getKey());
            if (description != null) {
                code.append(" – ").append(description);
            }
            code.append('\n');
            List<String> bodies = new ArrayList<>();
            for (int i = 0; i < branches.size(); i++) {
                String value = variable.getValue().get(i);
                bodies.add(value == null ? null : "return " + value + ";");
            }
            code.append(wrap(ifChain(branches, bodies), context, loop));
            out.variableRules().add(code.toString());
        }

        // Anzeige-Regeln: Zweige mit Inhalt (DCF-Content, eingebundene Bausteine)
        if (content.stream().anyMatch(c -> !c.isEmpty())) {
            List<String> bodies = new ArrayList<>();
            for (List<String> shown : content) {
                bodies.add(shown.isEmpty() ? null : String.join("\n", shown));
            }
            out.displayRules().add("// Anzeige-Regel\n" + wrap(ifChain(branches, bodies), context, loop));
        }

        // Verschachtelte Bedingungen innerhalb der Zweige
        List<String> previous = new ArrayList<>();
        for (Branch branch : branches) {
            String own = branch.condition() != null ? branch.condition() : negateAll(previous);
            if (branch.body() != null) {
                process(branch.body(), and(context, own), loop, out);
            }
            if (branch.condition() != null) {
                previous.add(branch.condition());
            }
        }
    }

    /**
     * if/else-if/else nur über die Zweige mit Inhalt. Beginnt die Kette mit einem else-Zweig
     * (alle vorherigen ohne Inhalt), wird daraus ein if mit der Verneinung der vorherigen Bedingungen.
     */
    private static String ifChain(List<Branch> branches, List<String> bodies) {
        StringBuilder code = new StringBuilder();
        List<String> previous = new ArrayList<>();
        boolean first = true;
        for (int i = 0; i < branches.size(); i++) {
            Branch branch = branches.get(i);
            String body = bodies.get(i);
            if (body != null) {
                if (branch.condition() == null && !first) {
                    code.append(" else {\n");
                } else {
                    String condition = branch.condition() != null ? branch.condition() : negateAll(previous);
                    code.append(first ? "if (" : " else if (").append(stripOuterParentheses(condition)).append(") {\n");
                }
                code.append(indent(body)).append("\n}");
                first = false;
            }
            if (branch.condition() != null) {
                previous.add(branch.condition());
            }
        }
        return code.append('\n').toString();
    }

    private static String wrap(String code, String context, String loop) {
        String result = code;
        if (context != null) {
            result = "if (" + stripOuterParentheses(context) + ") {\n" + indent(result.stripTrailing()) + "\n}\n";
        }
        if (loop != null && !loop.isBlank()) {
            result = "// für jedes Element aus " + loop + "\n" + result;
        }
        return result;
    }

    // ---------------------------------------------------------------- Anzeige (DCF-Content)

    /** Inhalt eines Zweigs als display(…)/include(…)-Zeilen. */
    private List<String> display(Element element) {
        List<String> lines = new ArrayList<>();
        String tag = local(element);
        switch (tag) {
            case "textblock" -> {
                String id = element.getAttribute("textblockID").trim();
                if (!id.isEmpty()) {
                    lines.add("include(\"" + id + "\");" + describe(id));
                    return lines;
                }
            }
            case "group" -> {
                String description = element.getAttribute("description").trim();
                lines.add("// Gruppe" + (description.isEmpty() ? "" : " „" + description + "“"));
                for (Element child : elements(element)) {
                    lines.addAll(display(child));
                }
                return lines;
            }
            case "groupentry" -> {
                for (Element child : elements(element)) {
                    for (String line : display(child)) {
                        lines.add(line.startsWith("include(") ? line + groupEntrySuffix(element, line) : line);
                    }
                }
                return lines;
            }
            default -> { }
        }
        String text = contentText(element).replaceAll("\\s+", " ").trim();
        if (!text.isEmpty()) {
            lines.add("display(`" + text.replace("`", "\\`") + "`); // <" + element.getTagName() + ">");
        }
        return lines;
    }

    private String describe(String id) {
        List<XmlFile> targets = lookup.apply(id);
        if (targets.size() == 1 && targets.get(0).description() != null) {
            return " // " + targets.get(0).description();
        }
        return targets.isEmpty() ? " // nicht gefunden" : "";
    }

    private static String groupEntrySuffix(Element entry, String line) {
        String name = entry.getAttribute("name").trim();
        if (name.isEmpty()) {
            return "";
        }
        return (line.contains("//") ? ", " : " // ") + "groupentry " + name;
    }

    /** Text des Inhalts; Variablen erscheinen als ${name}, verschachtelte Bedingungen werden übersprungen. */
    private static String contentText(Node node) {
        StringBuilder out = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Text text) {
                out.append(text.getData());
            } else if (child instanceof Element element) {
                switch (local(element)) {
                    case "variable" -> out.append("${").append(variableName(element)).append('}');
                    case "conditions", "meta" -> { }
                    case "p", "tr", "li", "br" -> out.append(' ').append(contentText(element)).append(' ');
                    default -> out.append(contentText(element));
                }
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- Ausdrücke

    /** Die Bedingung eines {@code <conditions>}-Blocks (alles außer then/else). */
    private static String condition(Element conditions) {
        List<Element> parts = new ArrayList<>();
        for (Element child : elements(conditions)) {
            String tag = local(child);
            if (!tag.equals("then") && !tag.equals("else")) {
                parts.add(child);
            }
        }
        return sequence(parts);
    }

    private static String expression(Element element) {
        String tag = local(element);
        String operator = OPERATORS.get(tag);
        if (operator != null && elements(element).isEmpty()) {
            return operator;
        }
        String result = switch (tag) {
            case "variable" -> variableName(element);
            case "conditionvalue", "const" -> quote(element.getTextContent().replaceAll("\\s+", " ").trim());
            case "function" -> element.getAttribute("name") + "(" + String.join(", ",
                    elements(element).stream().map(ScriptGenerator::expression).toList()) + ")";
            case "cmd" -> element.getAttribute("name") + "(" + concatenation(element) + ")";
            case "parenthesis" -> "(" + sequence(elements(element)) + ")";
            case "condition" -> sequence(elements(element));
            default -> {
                List<Element> children = elements(element);
                String inner = children.isEmpty() ? quote(element.getTextContent().trim()) : sequence(children);
                yield element.getTagName() + "(" + inner + ")";
            }
        };
        if ("true".equalsIgnoreCase(element.getAttribute("negation"))) {
            return "!(" + result + ")";
        }
        return result;
    }

    /** {@code <cmd>} mit Variablen und Textstücken, z. B. vorname " " nachname → vorname + " " + nachname. */
    private static String concatenation(Element cmd) {
        List<String> parts = new ArrayList<>();
        for (Node child = cmd.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                parts.add(expression(element));
            } else if (child instanceof Text text) {
                String value = text.getData().trim();
                if (!value.isEmpty()) {
                    parts.add(value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2 ? value : quote(value));
                }
            }
        }
        return String.join(" + ", parts);
    }

    private static String sequence(List<Element> elements) {
        return String.join(" ", elements.stream().map(ScriptGenerator::expression).toList());
    }

    private static String variableName(Element variable) {
        String name = variable.getAttribute("name").trim();
        return name.isEmpty() ? variable.getAttribute("uname").trim() : name;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String and(String context, String condition) {
        if (context == null) {
            return condition;
        }
        return "(" + context + ") && (" + condition + ")";
    }

    private static String negateAll(List<String> conditions) {
        if (conditions.isEmpty()) {
            return "true";
        }
        return String.join(" && ", conditions.stream().map(c -> "!(" + c + ")").toList());
    }

    /** „(a && b)“ → „a && b“, aber nicht „(a) || (b)“ – nur wenn die äußeren Klammern zusammengehören. */
    static String stripOuterParentheses(String condition) {
        String result = condition.trim();
        while (result.startsWith("(") && result.endsWith(")") && closingOfFirst(result) == result.length() - 1) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static int closingOfFirst(String text) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString && c == '(') {
                depth++;
            } else if (!inString && c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String indent(String code) {
        return INDENT + code.replace("\n", "\n" + INDENT);
    }

    private static boolean isElseBlock(Element conditions) {
        for (Node child = conditions.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Comment comment && comment.getData().toUpperCase(Locale.ROOT).contains("ELSE")) {
                return true;
            }
            if (child instanceof Element) {
                return false;
            }
        }
        return false;
    }

    private static Element child(Element parent, String tag) {
        for (Element child : elements(parent)) {
            if (local(child).equals(tag)) {
                return child;
            }
        }
        return null;
    }

    private static List<Element> elements(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static String local(Element element) {
        return ScanOptions.localName(element.getTagName());
    }
}
