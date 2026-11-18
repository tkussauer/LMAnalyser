package de.xmltree;

import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Vierte Anwendung: liest – wie der Tree-Viewer ab einer Vorlage – alle Bedingungen der
 * erreichbaren Bausteine aus und schreibt sie als Skript ({@code <Vorlage>_Script.js}):
 * Variablen-Regeln und, gesondert, Anzeige-Regeln (DCF-Content).
 */
public final class ConditionScript {

    private static final String USAGE = """
            Aufruf:
              java -cp xml-tree-viewer.jar de.xmltree.ConditionScript --start <vorlage|verz> [Optionen]

            Optionen:
              --start <wert>      Vorlage (Pfad), ID/Description einer Vorlage oder ein Verzeichnis mit
                                  Vorlagen (rekursiv; je Vorlage eine Skriptdatei) (Pflicht)
              --dir <verz>        Verzeichnis mit den Bausteinen (rekursiv); mehrfach angebbar
                                  (Standard: Verzeichnis der Vorlage bzw. das Vorlagen-Verzeichnis)
              --out <verz>        Zielverzeichnis (Standard: aktuelles Verzeichnis);
                                  Dateiname: <Vorlage>_Script.js; zusätzlich Variablen_Gesamt_Script.txt mit
                                  allen Variablen-Regeln aller Vorlagen ohne Dubletten (ohne Anzeige-Regeln)
              --id-names, --desc-names, --ref-names, --ext, --ignore-case   wie beim Tree-Viewer
              -h, --help          Diese Hilfe
            """;

    private ConditionScript() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException e) {
            System.err.println("Fehler: " + e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            System.exit(2);
        }
    }

    static int run(String[] args) {
        String start = null;
        Path out = Path.of(".");
        List<Path> dirs = new ArrayList<>();
        Set<String> idNames = ScanOptions.DEFAULT_ID_NAMES;
        Set<String> descNames = ScanOptions.DEFAULT_DESCRIPTION_NAMES;
        Set<String> refNames = ScanOptions.DEFAULT_REFERENCE_NAMES;
        Set<String> extensions = ScanOptions.DEFAULT_EXTENSIONS;
        boolean ignoreCase = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> {
                    System.out.println(USAGE);
                    return 0;
                }
                case "--start" -> start = Main.next(args, ++i, arg);
                case "--dir" -> dirs.add(Path.of(Main.next(args, ++i, arg)));
                case "--out" -> out = Path.of(Main.next(args, ++i, arg));
                case "--id-names" -> idNames = Main.names(Main.next(args, ++i, arg));
                case "--desc-names" -> descNames = Main.names(Main.next(args, ++i, arg));
                case "--ref-names" -> {
                    String value = Main.next(args, ++i, arg);
                    refNames = value.trim().equals("*") ? Set.of() : Main.names(value);
                }
                case "--ext" -> extensions = Main.names(Main.next(args, ++i, arg));
                case "--ignore-case" -> ignoreCase = true;
                default -> throw new IllegalArgumentException("Unbekannte Option: " + arg);
            }
        }
        if (start == null) {
            throw new IllegalArgumentException("--start fehlt");
        }
        ScanOptions options = new ScanOptions(idNames, descNames, refNames, ignoreCase, extensions);
        XmlScanner scanner = new XmlScanner(options);
        Path startPath = Path.of(start);
        boolean startIsDir = Files.isDirectory(startPath);
        boolean startIsFile = Files.isRegularFile(startPath);
        if (dirs.isEmpty()) {
            dirs.add(startIsDir ? startPath : startIsFile ? startPath.toAbsolutePath().getParent() : Path.of("."));
        }
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException("Kein Verzeichnis: " + dir);
            }
        }

        // Index aus Bausteinen und Vorlagen, jede Datei nur einmal
        Map<Path, XmlFile> byPath = new LinkedHashMap<>();
        for (XmlFile file : scanner.scanDirectories(dirs)) {
            byPath.put(normalize(file.path()), file);
        }
        List<XmlFile> templates = new ArrayList<>();
        if (startIsDir) {
            Path root = normalize(startPath);
            List<XmlFile> found = new ArrayList<>(scanner.scanDirectories(List.of(startPath)));
            found.sort(Comparator.comparingInt((XmlFile f) -> root.relativize(normalize(f.path())).getNameCount())
                    .thenComparing(f -> root.relativize(normalize(f.path())).toString().replace('\\', '/')
                            .toLowerCase(Locale.ROOT)));
            for (XmlFile template : found) {
                templates.add(byPath.computeIfAbsent(normalize(template.path()), p -> template));
            }
        } else if (startIsFile) {
            XmlFile template = byPath.get(normalize(startPath));
            if (template == null) {
                try {
                    template = scanner.scanFile(startPath);
                } catch (IOException | SAXException e) {
                    throw new IllegalArgumentException("Vorlage nicht lesbar oder kein gültiges XML: " + startPath
                            + " (" + e.getMessage() + ")");
                }
                byPath.put(normalize(startPath), template);
            }
            templates.add(template);
        }
        TreeBuilder builder = new TreeBuilder(new ArrayList<>(byPath.values()), options);
        if (!startIsDir && !startIsFile) {
            List<XmlFile> hits = builder.lookup(start);
            if (hits.size() != 1) {
                throw new IllegalArgumentException(hits.isEmpty()
                        ? "Keine Datei mit Pfad, ID oder Description '" + start + "' gefunden"
                        : "'" + start + "' ist mehrdeutig");
            }
            templates.add(hits.get(0));
        }
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("Keine Vorlagen mit den Endungen " + options.extensions() + " in " + startPath);
        }

        ScriptGenerator generator = new ScriptGenerator(builder::lookup);
        Map<XmlFile, ScriptGenerator.Rules> cache = new HashMap<>();
        Set<String> usedNames = new HashSet<>(Set.of(TOTAL_FILE.toLowerCase(Locale.ROOT)));
        Map<String, TotalRule> total = new LinkedHashMap<>();
        int failed = 0;
        for (XmlFile template : templates) {
            Path target = uniqueName(out.resolve(baseName(template) + "_Script.js"), usedNames);
            try {
                Set<XmlFile> bausteine = builder.reachable(template);
                String script = render(template, bausteine, generator, cache);
                collectTotal(template, bausteine, cache, total);
                Path parent = target.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(target, script, StandardCharsets.UTF_8);
                System.out.println("  " + template.displayName() + " -> " + target.toAbsolutePath());
            } catch (IOException | RuntimeException e) {
                failed++;
                System.err.println("  Fehler bei " + template.path() + ": " + e.getMessage());
            }
        }
        Path totalFile = out.resolve(TOTAL_FILE);
        try {
            Path parent = totalFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(totalFile, renderTotal(total, templates.size() - failed), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Fehler beim Schreiben von " + totalFile + ": " + e.getMessage());
            return 1;
        }
        System.out.printf("%d Vorlagen verarbeitet (%d fehlgeschlagen).%n", templates.size() - failed, failed);
        System.out.println("Gesamtdatei (" + total.size() + " Variablen-Regeln ohne Dubletten): " + totalFile.toAbsolutePath());
        return failed == 0 ? 0 : 1;
    }

    /** Name der Gesamtdatei aller Variablen-Regeln. */
    static final String TOTAL_FILE = "Variablen_Gesamt_Script.txt";

    /** Eine Variablen-Regel der Gesamtdatei mit allen Fundstellen. */
    static final class TotalRule {
        final String variable;
        final String code;
        String description;
        int occurrences;
        final Set<String> bausteine = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        final Set<String> vorlagen = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        TotalRule(String variable, String code) {
            this.variable = variable;
            this.code = code;
        }
    }

    /**
     * Nimmt die Variablen-Regeln einer Vorlage in die Gesamtliste auf. Dublette = gleiche Variable und
     * gleiche Bedingungslogik (gleicher Code); die Beschreibung zählt nicht mit.
     */
    static void collectTotal(XmlFile template, Iterable<XmlFile> bausteine, Map<XmlFile, ScriptGenerator.Rules> cache,
                             Map<String, TotalRule> total) {
        for (XmlFile baustein : bausteine) {
            ScriptGenerator.Rules rules = cache.get(baustein);
            if (rules == null) {
                continue;
            }
            for (String rule : rules.variableRules()) {
                int newline = rule.indexOf('\n');
                String header = newline < 0 ? rule : rule.substring(0, newline);
                if (!header.startsWith("// Variable ")) {
                    continue;
                }
                String code = newline < 0 ? "" : rule.substring(newline + 1).stripTrailing();
                String name = header.substring("// Variable ".length());
                String description = null;
                int dash = name.indexOf(" – ");
                if (dash >= 0) {
                    description = name.substring(dash + 3);
                    name = name.substring(0, dash);
                }
                String variable = name.trim();
                TotalRule entry = total.computeIfAbsent(variable + "\n" + code, k -> new TotalRule(variable, code));
                if (entry.description == null) {
                    entry.description = description;
                }
                entry.occurrences++;
                entry.bausteine.add(label(baustein));
                entry.vorlagen.add(baseName(template));
            }
        }
    }

    /** Gesamtdatei: alle Variablen-Regeln ohne Dubletten, sortiert nach Variable, ohne Anzeige-Regeln. */
    static String renderTotal(Map<String, TotalRule> total, int templateCount) {
        List<TotalRule> rules = new ArrayList<>(total.values());
        rules.sort(Comparator.comparing((TotalRule r) -> r.variable, String.CASE_INSENSITIVE_ORDER));
        int occurrences = rules.stream().mapToInt(r -> r.occurrences).sum();
        long variables = rules.stream().map(r -> r.variable.toLowerCase(Locale.ROOT)).distinct().count();
        StringBuilder text = new StringBuilder();
        text.append("// Variablen-Regeln aller Vorlagen (ohne Anzeige-Regeln)\n")
                .append("// erzeugt ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")))
                .append(" · ").append(templateCount).append(" Vorlagen · ").append(variables).append(" Variablen · ")
                .append(rules.size()).append(" Regeln · ").append(occurrences - rules.size()).append(" Dubletten entfernt\n")
                .append("// Dublette = gleiche Variable mit gleicher Bedingungslogik\n\n");
        String previous = null;
        for (TotalRule rule : rules) {
            if (!rule.variable.equalsIgnoreCase(previous == null ? "" : previous)) {
                text.append("// ").append("=".repeat(76)).append('\n');
            }
            previous = rule.variable;
            text.append("// Variable ").append(rule.variable);
            if (rule.description != null) {
                text.append(" – ").append(rule.description);
            }
            text.append('\n')
                    .append("// Bausteine: ").append(String.join(", ", rule.bausteine)).append('\n')
                    .append("// Vorlagen (").append(rule.vorlagen.size()).append("): ")
                    .append(String.join(", ", rule.vorlagen)).append('\n')
                    .append(rule.code).append("\n\n");
        }
        if (rules.isEmpty()) {
            text.append("// keine Variablen-Regeln gefunden\n");
        }
        return text.toString();
    }

    /** Skript einer Vorlage: Kopf, Variablen-Regeln, Anzeige-Regeln – je Baustein ein Abschnitt. */
    static String render(XmlFile template, Iterable<XmlFile> bausteine, ScriptGenerator generator,
                         Map<XmlFile, ScriptGenerator.Rules> cache) {
        Map<XmlFile, ScriptGenerator.Rules> rules = new LinkedHashMap<>();
        int variableCount = 0;
        int displayCount = 0;
        for (XmlFile baustein : bausteine) {
            ScriptGenerator.Rules r = cache.computeIfAbsent(baustein, f -> {
                try {
                    return generator.generate(f.path());
                } catch (IOException | SAXException e) {
                    return new ScriptGenerator.Rules(List.of("// Datei nicht lesbar: " + e.getMessage() + "\n"), List.of());
                }
            });
            if (!r.isEmpty()) {
                rules.put(baustein, r);
                variableCount += r.variableRules().size();
                displayCount += r.displayRules().size();
            }
        }

        StringBuilder script = new StringBuilder();
        script.append("// Vorlage: ").append(label(template)).append(" (").append(template.displayName()).append(")\n")
                .append("// erzeugt ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")))
                .append(" · ").append(variableCount).append(" Variablen-Regeln · ")
                .append(displayCount).append(" Anzeige-Regeln\n\n");
        section(script, "VARIABLEN-REGELN", rules, true);
        section(script, "ANZEIGE-REGELN (DCF-Content)", rules, false);
        return script.toString();
    }

    private static void section(StringBuilder script, String title, Map<XmlFile, ScriptGenerator.Rules> rules,
                                boolean variables) {
        script.append("// ").append("=".repeat(76)).append('\n')
                .append("// ").append(title).append('\n')
                .append("// ").append("=".repeat(76)).append("\n\n");
        boolean any = false;
        for (Map.Entry<XmlFile, ScriptGenerator.Rules> entry : rules.entrySet()) {
            List<String> list = variables ? entry.getValue().variableRules() : entry.getValue().displayRules();
            if (list.isEmpty()) {
                continue;
            }
            any = true;
            script.append("// --- Baustein ").append(label(entry.getKey())).append(" (")
                    .append(entry.getKey().displayName()).append(")\n\n");
            for (String rule : list) {
                script.append(rule).append('\n');
            }
        }
        if (!any) {
            script.append("// keine\n\n");
        }
    }

    private static String label(XmlFile file) {
        String id = file.id() != null ? file.id() : file.displayName();
        return file.description() != null ? id + " – " + file.description() : id;
    }

    private static String baseName(XmlFile file) {
        String name = file.path().getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Path uniqueName(Path target, Set<String> used) {
        String name = target.getFileName().toString();
        String base = name.substring(0, name.length() - ".js".length());
        String candidate = name;
        for (int n = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); n++) {
            candidate = base + "_" + n + ".js";
        }
        return target.resolveSibling(candidate);
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
