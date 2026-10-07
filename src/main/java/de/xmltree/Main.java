package de.xmltree;

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
              --start <wert>      Referenzdatei (Pfad) oder ID/Description der Startvorlage (Pflicht)
              --dir <verz>        Verzeichnis mit CML-/XML-Dateien, rekursiv; mehrfach angebbar
                                  (Standard: Verzeichnis der Startdatei bzw. aktuelles Verzeichnis)
              --out <datei>       Ziel-HTML-Datei (Standard: tree.html)
              --id-names <a,b>    Attribut-/Elementnamen der ID am Root-Element (Standard: id)
              --desc-names <a,b>  Attribut-/Elementnamen der Description (Standard: description)
              --ref-names <a,b>   Nur diese Attribute/Elemente als Referenz werten. Ohne Angabe wird
                                  jeder Attributwert und Elementtext mit allen IDs/Descriptions verglichen.
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
        Path out = Path.of("tree.html");
        List<Path> dirs = new ArrayList<>();
        Set<String> idNames = Set.of("id");
        Set<String> descNames = Set.of("description");
        Set<String> refNames = Set.of();
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
                case "--ref-names" -> refNames = names(next(args, ++i, arg));
                case "--ignore-case" -> ignoreCase = true;
                case "--ext" -> extensions = names(next(args, ++i, arg));
                default -> throw new IllegalArgumentException("Unbekannte Option: " + arg);
            }
        }
        if (start == null) {
            throw new IllegalArgumentException("--start fehlt");
        }

        Path startPath = Path.of(start);
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

        ScanOptions options = new ScanOptions(idNames, descNames, refNames, ignoreCase, extensions);
        XmlScanner scanner = new XmlScanner(options);
        List<XmlFile> files = scanner.scanDirectories(dirs);
        TreeBuilder builder = new TreeBuilder(files, options);

        XmlFile startFile = findStart(start, startPath, startIsFile, files, builder, options);

        TreeNode tree = builder.build(startFile);
        List<String> warnings = new ArrayList<>(scanner.warnings());
        warnings.addAll(builder.warnings());

        Path baseDir = dirs.size() == 1 ? dirs.get(0) : null;
        String html = new HtmlRenderer(baseDir).render(tree, files.size(), warnings);
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

    private static XmlFile findStart(String start, Path startPath, boolean startIsFile,
                                     List<XmlFile> files, TreeBuilder builder, ScanOptions options) {
        if (startIsFile) {
            Path wanted = startPath.toAbsolutePath().normalize();
            return files.stream()
                    .filter(f -> f.path().toAbsolutePath().normalize().equals(wanted))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Startdatei liegt nicht in den eingelesenen Verzeichnissen, hat keine der Endungen "
                                    + options.extensions() + " oder ist kein gültiges XML: "
                                    + startPath));
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

    private static String next(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Wert für " + option + " fehlt");
        }
        return args[i];
    }

    private static Set<String> names(String csv) {
        return Set.copyOf(Arrays.asList(csv.split(",")));
    }
}
