package de.xmltree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @TempDir
    Path dir;

    @Test
    void htmlFileIsNamedAfterTheTemplate() throws Exception {
        Path vorlagen = Files.createDirectories(dir.resolve("Vorlagen"));
        Path bausteine = Files.createDirectories(dir.resolve("Bausteine"));
        Path vorlage = vorlagen.resolve("HL_KO_LE_DVL_A_ANGEBOT_ANTRAG_SBU_SEU.cml");
        Files.writeString(vorlage, "<document ID='1'><textblock textblockID='2'/></document>");
        Files.writeString(bausteine.resolve("b.cml"), "<textblock ID='2'/>");
        Path ergebnis = dir.resolve("Ergebnis");

        assertEquals(0, Main.run(new String[]{"--start", vorlage.toString(), "--dir", bausteine.toString(),
                "--out", ergebnis.toString()}));

        assertTrue(Files.isRegularFile(ergebnis.resolve("HL_KO_LE_DVL_A_ANGEBOT_ANTRAG_SBU_SEU.html")));
    }

    @Test
    void startDirectoryProcessesEveryTemplate() throws Exception {
        Path vorlagen = Files.createDirectories(dir.resolve("Vorlagen"));
        Path unterordner = Files.createDirectories(vorlagen.resolve("alt"));
        Path bausteine = Files.createDirectories(dir.resolve("Bausteine"));
        Files.writeString(vorlagen.resolve("ANGEBOT.cml"), "<document ID='V1' Description='Angebot'>"
                + "<textblock textblockID='B1'/><textblock textblockID='FEHLT'/></document>");
        Files.writeString(vorlagen.resolve("ANTRAG.cml"), "<document ID='V2'><textblock textblockID='B1'/></document>");
        Files.writeString(unterordner.resolve("ANGEBOT.cml"), "<document ID='V3'/>");
        Files.writeString(bausteine.resolve("b1.cml"), "<textblock ID='B1'/>");
        Path ergebnis = dir.resolve("Ergebnis");

        assertEquals(0, Main.run(new String[]{"--start", vorlagen.toString(), "--dir", bausteine.toString(),
                "--out", ergebnis.toString()}));

        assertTrue(Files.isRegularFile(ergebnis.resolve("ANGEBOT.html")));
        assertTrue(Files.isRegularFile(ergebnis.resolve("ANTRAG.html")));
        // gleicher Dateiname im Unterordner wird nicht überschrieben
        assertTrue(Files.isRegularFile(ergebnis.resolve("ANGEBOT_2.html")));
        String index = Files.readString(ergebnis.resolve("index.html"));
        assertTrue(index.contains("<a href=\"ANGEBOT.html\">ANGEBOT.cml</a></td><td>V1</td><td>Angebot</td>"), index);
        assertTrue(index.contains("<a href=\"ANTRAG.html\">"));
        assertTrue(index.contains("<a href=\"ANGEBOT_2.html\">"));
        assertTrue(index.contains("<td class=\"num unres\">1</td>"), "V1 hat einen nicht gefundenen Verweis");
        assertTrue(Files.readString(ergebnis.resolve("ANTRAG.html")).contains("ID</span> <span class=\"val\">B1"));
    }

    @Test
    void startDirectoryRequiresOutputDirectory() throws Exception {
        Path vorlagen = Files.createDirectories(dir.resolve("Vorlagen"));
        Files.writeString(vorlagen.resolve("v.cml"), "<document ID='1'/>");

        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Main.run(new String[]{"--start", vorlagen.toString(), "--out", dir.resolve("x.html").toString()}));
        assertTrue(e.getMessage().contains("muss --out ein Verzeichnis sein"));
    }

    @Test
    void explicitHtmlFileIsKept() throws Exception {
        Path vorlage = dir.resolve("v.cml");
        Files.writeString(vorlage, "<document ID='1'/>");
        Path ziel = dir.resolve("eigener-name.html");

        assertEquals(0, Main.run(new String[]{"--start", vorlage.toString(), "--out", ziel.toString()}));

        assertTrue(Files.isRegularFile(ziel));
    }
}
