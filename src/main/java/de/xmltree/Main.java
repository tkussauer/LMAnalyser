package de.xmltree;

import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Kommandozeilen-Einstieg: XML-Dateien einlesen, Baum ab Referenzdatei aufbauen, HTML schreiben. */
public final class Main {

    private static final String USAGE = """
            Aufruf:
              java -jar xml-tree-viewer.jar --start <datei|ID|Description> [Optionen]

            Optionen:
              --start <wert>      Referenzdatei (Pfad) oder ID/Description der Startvorlage (Pflicht).
                                  Ein Verzeichnis: jede Vorlage darin wird verarbeitet (eine HTML-Datei
                                  je Vorlage im --out-Verzeichnis plus Übersicht index.html).
              --dir <verz>        Verzeichnis, in dem die referenzierten Dateien liegen (rekursiv);
                                  mehrfach angebbar. Die Startdatei darf auch außerhalb liegen.
                                  (Standard: Verzeichnis der Startdatei bzw. das Vorlagen-Verzeichnis)
              --out <verz>        Zielverzeichnis; die Datei heißt wie die Vorlage, aber mit .html
                                  (z. B. ANGEBOT.cml -> ANGEBOT.html). Standard: aktuelles Verzeichnis.
                                  Endet der Wert auf .html, wird genau diese Datei geschrieben.
              --id-names <a,b>    Attribut-/Elementnamen der ID am Root-Element (Standard: id,textblockID,documentID)
              --desc-names <a,b>  Attribut-/Elementnamen der Description (Standard: description)
              --ref-names <a,b>   Attribute/Elemente, die Verweise enthalten (Standard: textblockID).
                                  '*' = jeden Attributwert und Elementtext mit allen IDs/Descriptions vergleichen.
              --ignore-case       Groß-/Kleinschreibung beim Referenzvergleich ignorieren
              --ext <a,b>         Dateiendungen der einzulesenden Dateien (Standard: cml), z. B. cml,xml
              -h, --help          Diese Hilfe
            """;

    private Main() {
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
        boolean ignoreCase = false;
        Set<String> extensions = ScanOptions.DEFAULT_EXTENSIONS;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> {
                    System.out.println(USAGE);
                    return 0;
                }
                case "--start" -> start = next(args, ++i, arg);
                case "--dir" -> dirs.add(Path.of(next(args, ++i, arg)));
                case "--out" -> out = Path.of(next(args, ++i, arg));
                case "--id-names" -> idNames = names(next(args, ++i, arg));
                case "--desc-names" -> descNames = names(next(args, ++i, arg));
                case "--ref-names" -> {
                    String value = next(args, ++i, arg);
                    refNames = value.trim().equals("*") ? Set.of() : names(value);
                }
                case "--ignore-case" -> ignoreCase = true;
                case "--ext" -> extensions = names(next(args, ++i, arg));
                case "--vorlagen" -> throw new IllegalArgumentException("--vorlagen gehört zum Variablen-Export (CSV)."
                        + " Dieser wird so gestartet: java -cp xml-tree-viewer.jar de.xmltree.VariableExport --vorlagen <verz> --dir <bausteine>.");
                default -> throw new IllegalArgumentException("Unbekannte Option: " + arg);
            }
        }
        if (start == null) {
            throw new IllegalArgumentException("--start fehlt");
        }

        Path startPath = Path.of(start);
        ScanOptions options = new ScanOptions(idNames, descNames, refNames, ignoreCase, extensions);
        if (Files.isDirectory(startPath)) {
            return runAll(startPath, out, dirs, options);
        }
        boolean startIsFile = Files.isRegularFile(startPath);
        if (dirs.isEmpty()) {
            Path parent = startIsFile ? startPath.toAbsolutePath().getParent() : Path.of(".");
            dirs.add(parent);
        }
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException("Kein Verzeichnis: " + dir);
            }
        }

        XmlScanner scanner = new XmlScanner(options);
        List<XmlFile> files = new ArrayList<>(scanner.scanDirectories(dirs));
        if (startIsFile) {
            // Die Vorlage darf auch außerhalb der Referenzverzeichnisse liegen.
            Path wanted = startPath.toAbsolutePath().normalize();
            boolean included = files.stream().anyMatch(f -> f.path().toAbsolutePath().normalize().equals(wanted));
            if (!included) {
                try {
                    files.add(scanner.scanFile(startPath));
                } catch (IOException | SAXException e) {
                    throw new IllegalArgumentException("Startdatei nicht lesbar oder kein gültiges XML: "
                            + startPath + " (" + e.getMessage() + ")");
                }
            }
        }
        TreeBuilder builder = new TreeBuilder(files, options);

        XmlFile startFile = findStart(start, startPath, startIsFile, files, builder);

        TreeNode tree = builder.build(startFile);
        List<String> warnings = new ArrayList<>(scanner.warnings());
        warnings.addAll(builder.warnings());

        Path baseDir = dirs.size() == 1 ? dirs.get(0) : null;
        String html = new HtmlRenderer(baseDir, builder::lookup).render(tree, files.size(), warnings);
        out = outputFile(out, startFile);
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Fehler beim Schreiben von " + out + ": " + e.getMessage());
            return 1;
        }

        System.out.printf("%d Dateien eingelesen, %d Knoten im Baum, %d Hinweise.%n",
                files.size(), tree.size(), warnings.size());
        warnings.forEach(w -> System.out.println("  Hinweis: " + w));
        System.out.println("HTML geschrieben: " + out.toAbsolutePath());
        return 0;
    }

    /**
     * {@code --start <verzeichnis>}: jede Datei im Verzeichnis (rekursiv) ist eine Vorlage. Je Vorlage
     * wird eine HTML-Datei ins {@code --out}-Verzeichnis geschrieben, dazu eine Übersicht index.html.
     */
    static int runAll(Path templateDir, Path out, List<Path> dirs, ScanOptions options) {
        String outName = out.getFileName() == null ? "" : out.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (outName.endsWith(".html") || outName.endsWith(".htm")) {
            throw new IllegalArgumentException("Bei --start <verzeichnis> muss --out ein Verzeichnis sein, keine Datei: " + out);
        }
        if (dirs.isEmpty()) {
            dirs.add(templateDir);
        }
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException("Kein Verzeichnis: " + dir);
            }
        }

        XmlScanner scanner = new XmlScanner(options);
        List<XmlFile> templates = scanner.scanDirectories(List.of(templateDir));
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("Keine Vorlagen mit den Endungen " + options.extensions() + " in " + templateDir);
        }
        // Index aus Bausteinen und Vorlagen, jede Datei nur einmal
        java.util.Map<Path, XmlFile> byPath = new java.util.LinkedHashMap<>();
        for (XmlFile file : scanner.scanDirectories(dirs)) {
            byPath.put(file.path().toAbsolutePath().normalize(), file);
        }
        for (XmlFile template : templates) {
            byPath.putIfAbsent(template.path().toAbsolutePath().normalize(), template);
        }
        List<XmlFile> files = new ArrayList<>(byPath.values());
        TreeBuilder builder = new TreeBuilder(files, options);
        List<String> warnings = new ArrayList<>(scanner.warnings());
        warnings.addAll(builder.warnings());
        Path baseDir = dirs.size() == 1 ? dirs.get(0) : null;

        java.util.Set<String> usedNames = new java.util.HashSet<>(Set.of("index.html"));
        List<HtmlRenderer.IndexRow> rows = new ArrayList<>();
        int failed = 0;
        for (XmlFile template : templates) {
            XmlFile start = byPath.get(template.path().toAbsolutePath().normalize());
            Path target = uniqueName(outputFile(out, start), usedNames);
            try {
                TreeNode tree = builder.build(start);
                String html = new HtmlRenderer(baseDir, builder::lookup).render(tree, files.size(), warnings);
                write(target, html);
                rows.add(new HtmlRenderer.IndexRow(start, target.getFileName().toString(), tree.size(),
                        tree.files().size(), tree.count(TreeNode.Status.UNRESOLVED)));
                System.out.println("  " + start.displayName() + " -> " + target.getFileName());
            } catch (IOException | RuntimeException e) {
                failed++;
                System.err.println("  Fehler bei " + start.path() + ": " + e.getMessage());
            }
        }
        Path index = out.resolve("index.html");
        try {
            write(index, HtmlRenderer.renderIndex(rows, templateDir));
        } catch (IOException e) {
            System.err.println("Fehler beim Schreiben von " + index + ": " + e.getMessage());
            return 1;
        }

        System.out.printf("%d Vorlagen verarbeitet (%d fehlgeschlagen), %d Dateien im Index, %d Hinweise.%n",
                rows.size(), failed, files.size(), warnings.size());
        warnings.forEach(w -> System.out.println("  Hinweis: " + w));
        System.out.println("Übersicht: " + index.toAbsolutePath());
        return failed == 0 ? 0 : 1;
    }

    /** Liegen zwei Vorlagen gleichen Namens in Unterordnern, bekommt die zweite den Zusatz _2, _3 … */
    private static Path uniqueName(Path target, java.util.Set<String> used) {
        String name = target.getFileName().toString();
        String base = name.substring(0, name.length() - ".html".length());
        String candidate = name;
        for (int n = 2; !used.add(candidate.toLowerCase(java.util.Locale.ROOT)); n++) {
            candidate = base + "_" + n + ".html";
        }
        return target.resolveSibling(candidate);
    }

    private static void write(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static XmlFile findStart(String start, Path startPath, boolean startIsFile,
                                     List<XmlFile> files, TreeBuilder builder) {
        if (startIsFile) {
            Path wanted = startPath.toAbsolutePath().normalize();
            return files.stream()
                    .filter(f -> f.path().toAbsolutePath().normalize().equals(wanted))
                    .findFirst()
                    .orElseThrow();
        }
        List<XmlFile> hits = builder.lookup(start);
        if (hits.isEmpty()) {
            throw new IllegalArgumentException("Keine Datei mit Pfad, ID oder Description '" + start + "' gefunden");
        }
        if (hits.size() > 1) {
            throw new IllegalArgumentException("'" + start + "' ist mehrdeutig: "
                    + hits.stream().map(f -> f.path().toString()).toList());
        }
        return hits.get(0);
    }

    /**
     * Zieldatei: ist {@code out} ein Verzeichnis, heißt die Datei wie die Vorlage mit der Endung
     * {@code .html}; endet {@code out} selbst auf .html/.htm, wird genau diese Datei verwendet.
     */
    static Path outputFile(Path out, XmlFile start) {
        String given = out.getFileName() == null ? "" : out.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (given.endsWith(".html") || given.endsWith(".htm")) {
            return out;
        }
        String name = start.path().getFileName().toString();
        int dot = name.lastIndexOf('.');
        return out.resolve((dot > 0 ? name.substring(0, dot) : name) + ".html");
    }

    static String next(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Wert für " + option + " fehlt");
        }
        return args[i];
    }

    static Set<String> names(String csv) {
        return Set.copyOf(Arrays.asList(csv.split(",")));
    }
}
