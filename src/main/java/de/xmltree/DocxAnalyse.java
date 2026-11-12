package de.xmltree;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Dritte Anwendung: Komplexitätsanalyse von Word-Vorlagen. Zählt Felder und Bedingungen getrennt
 * nach Inhalt, Kopfzeile und Fußzeile und schreibt eine Übersicht und eine Detailliste als CSV.
 */
public final class DocxAnalyse {

    static final List<String> SUMMARY_HEADER = List.of("Dokument", "Bereich", "Felder", "Seriendruckfelder",
            "Seriendruckfelder eindeutig", "Bedingungen", "Feldarten", "Datei");
    static final List<String> DETAIL_HEADER = List.of("Dokument", "Bereich", "Teil", "Art", "Feldart", "Name", "Datei");
    static final Set<String> EXTENSIONS = Set.of("docx", "dotx", "docm", "dotm");

    private static final String USAGE = """
            Aufruf:
              java -cp xml-tree-viewer.jar de.xmltree.DocxAnalyse --in <datei|verz> [--out <verz>]

            Optionen:
              --in <datei|verz>   Word-Vorlage oder Verzeichnis mit Vorlagen (rekursiv; docx, dotx, docm, dotm)
              --out <verz>        Zielverzeichnis (Standard: aktuelles Verzeichnis). Geschrieben werden
                                  docx-uebersicht.csv (je Dokument und Bereich) und docx-felder.csv (Details)
              -h, --help          Diese Hilfe
            """;

    private DocxAnalyse() {
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
        Path in = null;
        Path out = Path.of(".");
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> {
                    System.out.println(USAGE);
                    return 0;
                }
                case "--in" -> in = Path.of(Main.next(args, ++i, arg));
                case "--out" -> out = Path.of(Main.next(args, ++i, arg));
                default -> throw new IllegalArgumentException("Unbekannte Option: " + arg);
            }
        }
        if (in == null) {
            throw new IllegalArgumentException("--in fehlt");
        }
        List<Path> documents = documents(in);
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("Keine Word-Dateien (" + EXTENSIONS + ") gefunden: " + in);
        }

        DocxAnalyzer analyzer = new DocxAnalyzer();
        Path summaryFile = out.resolve("docx-uebersicht.csv");
        Path detailFile = out.resolve("docx-felder.csv");
        int failed = 0;
        try (BufferedWriter summary = VariableExport.newCsv(summaryFile);
             BufferedWriter detail = VariableExport.newCsv(detailFile)) {
            VariableExport.writeRow(summary, SUMMARY_HEADER);
            VariableExport.writeRow(detail, DETAIL_HEADER);
            for (Path document : documents) {
                String name = stripExtension(document.getFileName().toString());
                List<DocxAnalyzer.Item> items;
                try {
                    items = analyzer.analyze(document);
                } catch (IOException | org.xml.sax.SAXException | RuntimeException e) {
                    failed++;
                    System.err.println("  Fehler bei " + document + ": " + e.getMessage());
                    continue;
                }
                for (DocxAnalyzer.Area area : DocxAnalyzer.Area.values()) {
                    VariableExport.writeRow(summary, summaryRow(name, area, items, document));
                }
                for (DocxAnalyzer.Item item : items) {
                    VariableExport.writeRow(detail, List.of(name, item.area().label, item.part(),
                            item.condition() ? "Bedingung" : "Feld", item.type(), item.name(),
                            document.getFileName().toString()));
                }
                System.out.println("  " + document.getFileName() + ": " + shortSummary(items));
            }
        } catch (IOException e) {
            System.err.println("Fehler beim Schreiben: " + e.getMessage());
            return 1;
        }
        System.out.printf("%d Dokumente analysiert (%d fehlgeschlagen).%n", documents.size() - failed, failed);
        System.out.println("Übersicht: " + summaryFile.toAbsolutePath());
        System.out.println("Details:   " + detailFile.toAbsolutePath());
        return failed == 0 ? 0 : 1;
    }

    /** Eine Zeile je Dokument und Bereich – auch wenn der Bereich leer ist, damit 0 sichtbar wird. */
    static List<String> summaryRow(String name, DocxAnalyzer.Area area, List<DocxAnalyzer.Item> items, Path document) {
        int fields = 0;
        int merge = 0;
        int conditions = 0;
        Set<String> mergeNames = new LinkedHashSet<>();
        Map<String, Integer> types = new TreeMap<>();
        for (DocxAnalyzer.Item item : items) {
            if (item.area() != area) {
                continue;
            }
            if (item.condition()) {
                conditions++;
            } else {
                fields++;
                if (item.type().equals("MERGEFIELD")) {
                    merge++;
                    mergeNames.add(item.name());
                }
            }
            types.merge(item.type(), 1, Integer::sum);
        }
        List<String> typeList = new ArrayList<>();
        types.forEach((type, count) -> typeList.add(type + " " + count));
        return List.of(name, area.label, String.valueOf(fields), String.valueOf(merge),
                String.valueOf(mergeNames.size()), String.valueOf(conditions), String.join(", ", typeList),
                document.getFileName().toString());
    }

    private static String shortSummary(List<DocxAnalyzer.Item> items) {
        StringBuilder out = new StringBuilder();
        for (DocxAnalyzer.Area area : DocxAnalyzer.Area.values()) {
            long fields = items.stream().filter(i -> i.area() == area && !i.condition()).count();
            long conditions = items.stream().filter(i -> i.area() == area && i.condition()).count();
            if (out.length() > 0) {
                out.append(" | ");
            }
            out.append(area.label).append(' ').append(fields).append(" Felder, ").append(conditions).append(" Bedingungen");
        }
        return out.toString();
    }

    private static List<Path> documents(Path in) {
        if (Files.isRegularFile(in)) {
            return List.of(in);
        }
        if (!Files.isDirectory(in)) {
            throw new IllegalArgumentException("Weder Datei noch Verzeichnis: " + in);
        }
        try (Stream<Path> files = Files.walk(in)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String file = p.getFileName().toString();
                        int dot = file.lastIndexOf('.');
                        // ~$… sind Sperrdateien geöffneter Word-Dokumente
                        return !file.startsWith("~$") && dot > 0
                                && EXTENSIONS.contains(file.substring(dot + 1).toLowerCase(Locale.ROOT));
                    })
                    .sorted((a, b) -> a.toString().compareToIgnoreCase(b.toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("Verzeichnis nicht lesbar: " + in + " (" + e.getMessage() + ")");
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
