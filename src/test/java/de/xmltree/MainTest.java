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
    void explicitHtmlFileIsKept() throws Exception {
        Path vorlage = dir.resolve("v.cml");
        Files.writeString(vorlage, "<document ID='1'/>");
        Path ziel = dir.resolve("eigener-name.html");

        assertEquals(0, Main.run(new String[]{"--start", vorlage.toString(), "--out", ziel.toString()}));

        assertTrue(Files.isRegularFile(ziel));
    }
}
