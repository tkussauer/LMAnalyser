package de.xmltree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxAnalyseTest {

    private static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"";

    @TempDir
    Path dir;

    /** Komplexes Feld: begin – Feldcode – separate – Ergebnis – end; {@code inner} wird in den Code geschachtelt. */
    private static String field(String code, String inner) {
        return "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText>" + code + "</w:instrText></w:r>"
                + inner + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>x</w:t></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
    }

    private Path docx(Map<String, String> parts) throws Exception {
        Path file = dir.resolve("Vorlage.docx");
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private Path sample() throws Exception {
        String body = "<w:document " + W + "><w:body><w:p>"
                + field(" MERGEFIELD KUNDE_NAME \\* MERGEFORMAT ", "")
                + field(" MERGEFIELD KUNDE_NAME ", "")
                + field(" DATE \\@ \"dd.MM.yyyy\" ", "")
                + "</w:p><w:sdt><w:sdtPr><w:alias w:val=\"Vertragsart\"/></w:sdtPr><w:sdtContent/></w:sdt>"
                + "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdH\"/>"
                + "<w:footerReference w:type=\"first\" r:id=\"rIdF\"/></w:sectPr></w:body></w:document>";
        String header = "<w:hdr " + W + "><w:p>"
                + field(" IF ", field(" MERGEFIELD SPARTE ", "") + "<w:r><w:instrText> = \"KFZ\" \"Kfz-Vertrag\" \"\" </w:instrText></w:r>")
                + "</w:p></w:hdr>";
        String footer = "<w:ftr " + W + "><w:p><w:fldSimple w:instr=\" PAGE \"><w:r><w:t>1</w:t></w:r></w:fldSimple>"
                + field(" COMPARE 1 = 1 ", "") + "</w:p></w:ftr>";
        String rels = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdH\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header1.xml\"/>"
                + "<Relationship Id=\"rIdF\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>"
                + "</Relationships>";
        return docx(new java.util.LinkedHashMap<>(Map.of("word/document.xml", body, "word/header1.xml", header,
                "word/footer1.xml", footer, "word/_rels/document.xml.rels", rels)));
    }

    @Test
    void separatesFieldsAndConditionsByArea() throws Exception {
        List<DocxAnalyzer.Item> items = new DocxAnalyzer().analyze(sample());

        List<String> inhalt = items.stream().filter(i -> i.area() == DocxAnalyzer.Area.INHALT)
                .map(i -> i.type() + ":" + i.name()).toList();
        assertEquals(List.of("MERGEFIELD:KUNDE_NAME", "MERGEFIELD:KUNDE_NAME", "DATE:DATE \\@ \"dd.MM.yyyy\"",
                "Inhaltssteuerelement:Vertragsart"), inhalt);

        List<DocxAnalyzer.Item> kopf = items.stream().filter(i -> i.area() == DocxAnalyzer.Area.KOPFZEILE).toList();
        // Das MERGEFIELD in der Bedingung zählt als Feld, das IF als Bedingung
        assertEquals(2, kopf.size());
        assertEquals("MERGEFIELD:SPARTE:false", kopf.get(0).type() + ":" + kopf.get(0).name() + ":" + kopf.get(0).condition());
        assertTrue(kopf.get(1).condition());
        assertEquals("IF = \"KFZ\" \"Kfz-Vertrag\" \"\"", kopf.get(1).name());
        assertEquals("header1.xml (Standard)", kopf.get(1).part());

        List<DocxAnalyzer.Item> fuss = items.stream().filter(i -> i.area() == DocxAnalyzer.Area.FUSSZEILE).toList();
        assertEquals(List.of("PAGE:false", "COMPARE:true"), fuss.stream().map(i -> i.type() + ":" + i.condition()).toList());
        assertEquals("footer1.xml (erste Seite)", fuss.get(0).part());
    }

    @Test
    void writesSummaryAndDetailCsv() throws Exception {
        sample();
        Path out = dir.resolve("out");

        assertEquals(0, DocxAnalyse.run(new String[]{"--in", dir.toString(), "--out", out.toString()}));

        List<String> summary = Files.readAllLines(out.resolve("docx-uebersicht.csv"));
        assertEquals(List.of(
                "﻿" + String.join(";", DocxAnalyse.SUMMARY_HEADER),
                "Vorlage;Inhalt;4;2;1;0;DATE 1, Inhaltssteuerelement 1, MERGEFIELD 2;Vorlage.docx",
                "Vorlage;Kopfzeile;1;1;1;1;IF 1, MERGEFIELD 1;Vorlage.docx",
                "Vorlage;Fußzeile;1;0;0;1;COMPARE 1, PAGE 1;Vorlage.docx"), summary);
        List<String> detail = Files.readAllLines(out.resolve("docx-felder.csv"));
        assertEquals(9, detail.size());
        assertTrue(detail.stream().anyMatch(l -> l.startsWith("Vorlage;Kopfzeile;header1.xml (Standard);Bedingung;IF;")),
                detail.toString());
    }
}
