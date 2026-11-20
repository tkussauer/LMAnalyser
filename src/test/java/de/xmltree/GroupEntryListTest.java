package de.xmltree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupEntryListTest {

    @TempDir
    Path dir;

    @Test
    void collectsGroupentriesWithGroupFlagsAndTextblocks() throws Exception {
        Path file = dir.resolve("v.cml");
        Files.writeString(file, "<textblock ID='1'><meta><generic><entry name='x' val='y'/></generic></meta>"
                + "<conditions><condition><variable name='a'/><equalTo/><conditionValue>1</conditionValue></condition><then>"
                + "<group description='Tarifauswahl'>"
                + "<groupentry name='HL_A' description='Hinweis' selected='true' optional='true'><textblock textblockID='10'/></groupentry>"
                + "<groupentry name='HL_B' description='' selected='false' optional='false'><textblock textblockID='11'/>"
                + "<textblock textblockID='12'/></groupentry></group></then></conditions>"
                + "<groupentry name='HL_C'/></textblock>");
        XmlFile xml = new XmlScanner(ScanOptions.defaults()).scanFile(file);

        List<GroupEntryList.Entry> entries = GroupEntryList.collect(XmlScanner.newSecureBuilder(), xml);

        assertEquals(List.of("HL_A", "HL_B", "HL_C"), entries.stream().map(GroupEntryList.Entry::name).toList());
        GroupEntryList.Entry a = entries.get(0);
        assertEquals("Tarifauswahl", a.group());
        assertEquals("Hinweis", a.description());
        assertTrue(a.selected() && a.optional());
        assertEquals(List.of("11", "12"), entries.get(1).textblocks());
        assertEquals("", entries.get(2).group());
    }

    @Test
    void writesHtmlPerTemplateWithEntriesOfAllReachableBausteine() throws Exception {
        Path vorlagen = Files.createDirectories(dir.resolve("Vorlagen"));
        Path bausteine = Files.createDirectories(dir.resolve("Bausteine"));
        Files.writeString(vorlagen.resolve("HL_SBU.cml"), "<document ID='V'><group description='Kopf'>"
                + "<groupentry name='HL_KOPF'><textblock textblockID='B'/></groupentry></group></document>");
        Files.writeString(bausteine.resolve("b.cml"), "<textblock ID='B' Description='Baustein'>"
                + "<groupentry name='HL_INNEN' description='innen'><textblock textblockID='FEHLT'/></groupentry></textblock>");
        Path out = dir.resolve("out");

        assertEquals(0, GroupEntryList.run(new String[]{"--start", vorlagen.toString(), "--dir", bausteine.toString(),
                "--out", out.toString()}));

        String html = Files.readString(out.resolve("HL_SBU_Bloecke.html"));
        assertTrue(html.contains("2 Blöcke · 2 verschiedene Namen"), html);
        assertTrue(html.contains("<tr data-name=\"HL_KOPF\" data-ids=\"B\">"));
        assertTrue(html.contains("<tr data-name=\"HL_INNEN\" data-ids=\"FEHLT\">"));
        assertTrue(html.contains("<span class=\"code\">B</span> <span class=\"muted\">Baustein</span>"));
        assertTrue(html.contains("FEHLT</span> <span class=\"unres\">nicht gefunden</span>"));
        assertTrue(html.contains("id=\"build\">Als Liste ausgeben</button>"));
    }
}
