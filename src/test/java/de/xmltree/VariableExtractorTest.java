package de.xmltree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableExtractorTest {

    @TempDir
    Path dir;

    private List<VariableRef> extract(String xml) throws Exception {
        Path file = dir.resolve("t.cml");
        Files.writeString(file, xml);
        return new VariableExtractor().extract(file);
    }

    private static Set<VariableRef> scan(String text) {
        Set<VariableRef> out = new LinkedHashSet<>();
        VariableExtractor.scanText(text, "x", true, out);
        return out;
    }

    @Test
    void variableElementWithAllAttributes() throws Exception {
        List<VariableRef> vars = extract("<textblock ID='1'><set><variable name='dokument.fachdaten.dokumentDatenID'"
                + " uname='Data.BusinessData.dokument.fachdaten.dokumentDatenID' iterkey='Data.BusinessData'"
                + " datatype='string' automatic='true'/><equalTo/><conditionValue>1</conditionValue></set></textblock>");

        assertEquals(List.of(new VariableRef("dokument.fachdaten.dokumentDatenID",
                "Data.BusinessData.dokument.fachdaten.dokumentDatenID", "Data.BusinessData", "string", "true",
                VariableRef.Kind.VARIABLE_ELEMENT, "<variable>")), vars);
    }

    @Test
    void mappingNameToUname() {
        VariableRef ref = scan("dokument.fachdaten.berufBezug,Data.BusinessData.berufBezug").iterator().next();
        assertEquals(VariableRef.Kind.MAPPING, ref.kind());
        assertEquals("dokument.fachdaten.berufBezug", ref.name());
        assertEquals("Data.BusinessData.berufBezug", ref.uname());

        VariableRef quoted = scan("TAB\",Data.BusinessData.notFoundString\"").iterator().next();
        assertEquals("TAB", quoted.name());
        assertEquals("Data.BusinessData.notFoundString", quoted.uname());
    }

    @Test
    void nameListsVarNamesAndPaths() {
        assertEquals(List.of("varLeBerater", "varLeBeratungsdatum"),
                scan("varLeBerater, varLeBeratungsdatum").stream().map(VariableRef::name).toList());
        assertEquals(List.of(VariableRef.Kind.NAME_LIST, VariableRef.Kind.VAR_NAME),
                scan("vermittlerNr, VAR_KLAUSEL").stream().map(VariableRef::kind).toList());
        assertEquals(7, scan("VAR_LE_TARIFBEITRAG_TAB, VAR_LE_BEITRAGSENDE_RW, VAR_LE_BEITRAGSTYP_TAB_TEXT,"
                + " VAR_LE_BEITAB_TYP, VAR_LE_ZAHLBEITRAG_TAB, VAR_LE_BEITAB_FAELLIGKEIT, VAR_LE_BEITRAGSBEGINN_RW").size());
        assertEquals(VariableRef.Kind.NAME_LIST, scan("Anzahl_Fonds").iterator().next().kind());
        assertEquals(VariableRef.Kind.PATH, scan("system_BFSLeben.dokumentDaten.Doku.Dynamik").iterator().next().kind());
    }

    @Test
    void allPatternsFromTheSpecificationAreRecognized() {
        List<String> lines = List.of(
                "Data.BusinessData.bsprechs.bspRech.Versjahr_lfd,Data.BusinessData.bsprechs.Value.versjahrLfd",
                "Output_0.OB_fachlich,Data.OB_fachlich",
                "TAB\",Data.BusinessData.notFoundString\"",
                "system_BFSLeben.dokumentDaten.Rollen.Rolle.Telfax_Vorwahl,Data.BusinessData.telfaxVorwahl",
                "system_BFSLeben.dokumentDaten.BIB.BIB_Einmalige_Kost\",Data.BusinessData.BIB.bibEinmaligeKost\"",
                "Data.BusinessData.kennzeichenBuz,Data.BusinessData.kennzeichenBUZ",
                "BusinessData.BIBbib,BusinessData.BIB.bib",
                "Data.BusinessData.bspRechs.Value.leistungUbsp3",
                "VAR_KLAUSEL,Data.BusinessData.notFoundString",
                "DOPDATE,Data.BusinessData.notFoundString",
                "system_BFSLeben.dokumentDaten.vts.vt.Einm_Abko_Zillmer_Proz,Data.BusinessData.VT.einmAbkoZillmerProz",
                "VAR_LE_BETRAG_KUE_RKW_3",
                "system_BFSLeben.dokumentDaten.Doku.Dynamik",
                "VAR_LE_FONDSNAME_LANG",
                "varLeBerater, varLeBeratungsdatum",
                "vermittlerNr, VAR_KLAUSEL",
                "Anzahl_Fonds");
        for (String line : lines) {
            assertFalse(scan(line).isEmpty(), "nicht erkannt: " + line);
        }
    }

    @Test
    void literalsLabelsAndNormalTextAreIgnored() throws Exception {
        List<VariableRef> vars = extract("<textblock ID='1' Description='Tarif.Auswahl'>"
                + "<meta><generic><entry name='releaseName' val='LEBEN.P2'/></generic></meta>"
                + "<conditions><condition><variable name='a.b'/><equalTo/><conditionValue>Werkstatt</conditionValue>"
                + "</condition><then><group description='Gruppe'><groupentry name='HL_KO_LE_X' description='x'>"
                + "<textblock textblockID='2'/></groupentry></group></then></conditions>"
                + "<set><variable name='K'/><equalTo/><function name='LOOKUP'><const>VH_SD_KR_LUP_DSTL</const></function></set>"
                + "<p>Sehr geehrte Damen und Herren, z.B. auf www.vhv.de oder in datei.pdf</p></textblock>");

        assertEquals(List.of("a.b", "K"), vars.stream().map(VariableRef::name).toList());
    }

    @Test
    void cmdOrigIterationAndComments() throws Exception {
        List<VariableRef> vars = extract("<textblock ID='1'>"
                + "<!--＜textmultipleruleobject callId=100 description=BFSSchaden tforeference=system_BFSSchaden.dienstleister＞-->"
                + "<iteration description='repeat' name='rollen.rolle'>"
                + "<set><variable name='VAR_CC_NAME'/><equalTo/><cmd name='SMC_SAVE'"
                + " orig='rollen.rolle.akadgrad | &quot; &quot; | &amp;VAR_CC_NAME'/></set></iteration></textblock>");

        Set<String> names = new LinkedHashSet<>(vars.stream().map(v -> v.name() + "|" + v.kind()).toList());
        assertTrue(names.contains("system_BFSSchaden.dienstleister|PATH"), names.toString());
        assertTrue(names.contains("rollen.rolle|PATH"), names.toString());
        assertTrue(names.contains("VAR_CC_NAME|VARIABLE_ELEMENT"), names.toString());
        assertTrue(names.contains("rollen.rolle.akadgrad|PATH"), names.toString());
        assertTrue(names.contains("VAR_CC_NAME|VAR_NAME"), names.toString());
    }

    @Test
    void exportWritesOneRowPerTemplateBausteinAndVariable() throws Exception {
        Path vorlagen = Files.createDirectories(dir.resolve("vorlagen"));
        Path bausteine = Files.createDirectories(dir.resolve("bausteine"));
        Files.writeString(vorlagen.resolve("v1.cml"), "<document ID='V1' Description='Vorlage 1'>"
                + "<textblock textblockID='B1'/><textblock textblockID='B2'/></document>");
        Files.writeString(vorlagen.resolve("v2.cml"), "<document ID='V2'><textblock textblockID='B2'/></document>");
        Files.writeString(bausteine.resolve("b1.cml"), "<textblock ID='B1' Description='Eins'><set>"
                + "<variable name='x' uname='Data.x' iterkey='Data' datatype='string' automatic='true'/><equalTo/>"
                + "<conditionValue>1</conditionValue></set><textblock textblockID='B2'/></textblock>");
        Files.writeString(bausteine.resolve("b2.cml"), "<textblock ID='B2' Description='Zwei; mit Semikolon'>"
                + "<p>VAR_KLAUSEL</p></textblock>");
        Path out = dir.resolve("out.csv");

        assertEquals(0, VariableExport.run(new String[]{"--vorlagen", vorlagen.toString(), "--dir", bausteine.toString(),
                "--out", out.toString()}));

        List<String> lines = Files.readAllLines(out);
        assertEquals("﻿" + String.join(";", VariableExport.HEADER), lines.get(0));
        assertEquals(List.of(
                "V1;B1;x;Data.x;Data;string;true;variable-Element;<variable>;Vorlage 1;Eins;b1.cml",
                "V1;B2;VAR_KLAUSEL;;;;;VAR-Name;<p>;Vorlage 1;\"Zwei; mit Semikolon\";b2.cml",
                "V2;B2;VAR_KLAUSEL;;;;;VAR-Name;<p>;;\"Zwei; mit Semikolon\";b2.cml"), lines.subList(1, lines.size()));
    }
}
