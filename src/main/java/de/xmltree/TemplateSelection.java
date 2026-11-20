package de.xmltree;

import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gemeinsame Kommandozeile für Anwendungen, die eine oder mehrere Vorlagen verarbeiten:
 * {@code --start <vorlage|ID|verzeichnis> --dir <bausteine> --out <verz>} plus die Scan-Optionen.
 * Baut den Index aus Bausteinen und Vorlagen (jede Datei nur einmal).
 *
 * @param templates zu verarbeitende Vorlagen; bei einem Verzeichnis oberste Ebene zuerst, dann alphabetisch
 * @param builder   Index zum Auflösen der Verweise
 * @param out       Zielverzeichnis
 */
record TemplateSelection(List<XmlFile> templates, TreeBuilder builder, Path out) {

    /** @return {@code null}, wenn nur die Hilfe ausgegeben wurde */
    static TemplateSelection parse(String[] args, String usage) {
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
                    System.out.println(usage);
                    return null;
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

        return new TemplateSelection(templates, builder, out);
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
