package de.xmltree;

import org.xml.sax.SAXException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zweite Anwendung: durchläuft alle Vorlagen eines Verzeichnisses, löst je Vorlage wie der
 * Tree-Viewer die Bausteine auf und schreibt alle Variablen in eine CSV-Datei.
 */
public final class VariableExport {

    static final List<String> HEADER = List.of("Vorlage", "Baustein", "Variable", "uname", "iterkey", "datatype",
            "automatic", "Fundart", "Fundstelle", "Vorlage Description", "Baustein Description", "Datei");

    private static final String USAGE = """
            Aufruf:
              java -cp xml-tree-viewer.jar de.xmltree.VariableExport --vorlagen <verz> [Optionen]

            Optionen:
              --vorlagen <verz>   Verzeichnis mit den Vorlagen (rekursiv); jede Datei darin ist eine Vorlage (Pflicht)
              --dir <verz>        Verzeichnis mit den Bausteinen (rekursiv); mehrfach angebbar
                                  (Standard: Vorlagen-Verzeichnis)
              --out <datei>       Ziel-CSV-Datei (Standard: variablen.csv); Trennzeichen ';', UTF-8
              --id-names <a,b>    Attribut-/Elementnamen der ID am Root-Element (Standard: id,textblockID,documentID)
              --desc-names <a,b>  Attribut-/Elementnamen der Description (Standard: description)
              --ref-names <a,b>   Attribute/Elemente mit Verweisen (Standard: textblockID)
              --ext <a,b>         Dateiendungen (Standard: cml)
              --ignore-case       Groß-/Kleinschreibung beim Referenzvergleich ignorieren
              -h, --help          Diese Hilfe
            """;

    private VariableExport() {
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
        Path vorlagen = null;
        Path out = Path.of("variablen.csv");
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
                case "--vorlagen" -> vorlagen = Path.of(Main.next(args, ++i, arg));
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
        if (vorlagen == null) {
            throw new IllegalArgumentException("--vorlagen fehlt");
        }
        if (dirs.isEmpty()) {
            dirs.add(vorlagen);
        }
        for (Path dir : concat(vorlagen, dirs)) {
            if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException("Kein Verzeichnis: " + dir);
            }
        }

        ScanOptions options = new ScanOptions(idNames, descNames, refNames, ignoreCase, extensions);
        XmlScanner scanner = new XmlScanner(options);
        List<XmlFile> templates = scanner.scanDirectories(List.of(vorlagen));
        // Index aus Bausteinen und Vorlagen (Vorlagen können sich gegenseitig einbinden), ohne Doppelte
        Map<Path, XmlFile> byPath = new java.util.LinkedHashMap<>();
        for (XmlFile file : scanner.scanDirectories(dirs)) {
            byPath.put(file.path().toAbsolutePath().normalize(), file);
        }
        for (XmlFile template : templates) {
            byPath.putIfAbsent(template.path().toAbsolutePath().normalize(), template);
        }
        TreeBuilder builder = new TreeBuilder(new ArrayList<>(byPath.values()), options);
        List<String> warnings = new ArrayList<>(scanner.warnings());
        warnings.addAll(builder.warnings());

        VariableExtractor extractor = new VariableExtractor();
        Map<XmlFile, List<VariableRef>> cache = new HashMap<>();
        int rows = 0;
        try (BufferedWriter writer = newCsv(out)) {
            writeRow(writer, HEADER);
            for (XmlFile template : templates) {
                XmlFile start = byPath.get(template.path().toAbsolutePath().normalize());
                for (XmlFile baustein : builder.reachable(start)) {
                    List<VariableRef> variables = cache.computeIfAbsent(baustein, f -> extract(extractor, f, warnings));
                    for (VariableRef v : variables) {
                        writeRow(writer, List.of(key(start), key(baustein), v.name(), v.uname(), v.iterkey(),
                                v.datatype(), v.automatic(), v.kind().label(), v.location(),
                                nz(start.description()), nz(baustein.description()),
                                baustein.path().getFileName().toString()));
                        rows++;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Fehler beim Schreiben von " + out + ": " + e.getMessage());
            return 1;
        }

        System.out.printf("%d Vorlagen, %d Dateien im Index, %d Zeilen, %d Hinweise.%n",
                templates.size(), byPath.size(), rows, warnings.size());
        warnings.forEach(w -> System.out.println("  Hinweis: " + w));
        System.out.println("CSV geschrieben: " + out.toAbsolutePath());
        return 0;
    }

    private static List<VariableRef> extract(VariableExtractor extractor, XmlFile file, List<String> warnings) {
        try {
            return extractor.extract(file.path());
        } catch (IOException | SAXException e) {
            warnings.add("Variablen nicht lesbar (" + file.path() + "): " + e.getMessage());
            return List.of();
        }
    }

    /** CSV für Excel: UTF-8 mit BOM, damit Umlaute korrekt angezeigt werden. */
    private static BufferedWriter newCsv(Path out) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8);
        writer.write('\uFEFF');
        return writer;
    }

    static void writeRow(BufferedWriter writer, List<String> fields) throws IOException {
        List<String> escaped = new ArrayList<>(fields.size());
        for (String field : fields) {
            escaped.add(csv(field));
        }
        writer.write(String.join(";", escaped));
        writer.write("\r\n");
    }

    static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** ID der Datei, ersatzweise Description oder Dateiname. */
    private static String key(XmlFile file) {
        if (file.id() != null) {
            return file.id();
        }
        return file.description() != null ? file.description() : file.displayName();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Set<Path> concat(Path first, List<Path> rest) {
        Set<Path> all = new LinkedHashSet<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }
}
