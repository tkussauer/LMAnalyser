package de.xmltree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicParserTest {

    @TempDir
    Path dir;

    private List<LogicNode> logicOf(String xml) throws Exception {
        Path file = dir.resolve("t.cml");
        Files.writeString(file, xml);
        return new XmlScanner(ScanOptions.defaults()).scanFile(file).logic();
    }

    @Test
    void setBecomesAssignment() throws Exception {
        List<LogicNode> logic = logicOf("<textblock id='1'><set>"
                + "<variable name='VAR_CC_SELBSTBEHALT' uname='VAR_CC_SELBSTBEHALT' datatype='string'/><equalTo/>"
                + "<variable name='system_BFSSchaden.selbstbehalt' datatype='string'/></set></textblock>");

        LogicNode set = logic.get(0);
        assertEquals(LogicNode.Kind.SET, set.kind());
        assertEquals("VAR_CC_SELBSTBEHALT", set.title());
        assertEquals("system_BFSSchaden.selbstbehalt", set.detail());
    }

    @Test
    void conditionsWithParenthesesAndOperators() throws Exception {
        List<LogicNode> logic = logicOf("<textblock id='1'><conditions>"
                + "<parenthesis><condition><variable name='a'/><equalTo/><variable name='b'/></condition><and/>"
                + "<parenthesis><condition><variable name='n'/><notEqualTo/><conditionValue/></condition><or/>"
                + "<condition><variable name='v'/><notEqualTo/><conditionValue>X</conditionValue></condition>"
                + "</parenthesis></parenthesis>"
                + "<then><set><variable name='R'/><equalTo/><conditionValue>1</conditionValue></set></then>"
                + "</conditions></textblock>");

        LogicNode rule = logic.get(0);
        assertEquals(LogicNode.Kind.IF, rule.kind());
        assertEquals("(a = b UND (n ≠ \"\" ODER v ≠ \"X\"))", rule.title());
        assertEquals("R", rule.children().get(0).title());
        assertEquals("\"1\"", rule.children().get(0).detail());
    }

    @Test
    void functionsAndCommands() throws Exception {
        List<LogicNode> logic = logicOf("<textblock id='1'>"
                + "<set><variable name='K'/><equalTo/><function name='LOOKUP'><const>TAB</const>"
                + "<variable name='x'/><variable name='y'/></function></set>"
                + "<set><variable name='N'/><equalTo/><cmd name='SMC_SAVE' orig='a | &quot; &quot; | &amp;VAR_B'>"
                + "<variable name='a'/> \" \" <variable name='VAR_B'/></cmd></set></textblock>");

        assertEquals("LOOKUP(TAB, x, y)", logic.get(0).detail());
        assertEquals("SMC_SAVE(a | \" \" | VAR_B)", logic.get(1).detail());
    }

    @Test
    void iterationAndRuleComments() throws Exception {
        List<LogicNode> logic = logicOf("<textblock id='1'>"
                + "<!--＜textmultipleruleobject callId=100 description=BFSSchaden tforeference=system_BFSSchaden.dienstleister＞-->"
                + "<iteration description='repeat' name='system_BFSSchaden.dienstleister'><!--call 110-->"
                + "<!--＜textruleobject callId=110 description=Dienstleister-Details executionStrategy=ExecuteFirstMatchedRuleStrategy＞-->"
                + "<set><variable name='A'/><equalTo/><variable name='B'/></set><!--END call 110--></iteration>"
                + "</textblock>");

        assertEquals(2, logic.size());
        assertEquals("Regelgruppe 100 · BFSSchaden", logic.get(0).title());
        assertEquals("für system_BFSSchaden.dienstleister", logic.get(0).detail());

        LogicNode loop = logic.get(1);
        assertEquals(LogicNode.Kind.ITERATION, loop.kind());
        assertEquals("system_BFSSchaden.dienstleister", loop.title());
        assertNull(loop.detail());
        // „call 110“ / „END call 110“ entfallen, die Regelbeschreibung bleibt
        assertEquals(2, loop.children().size());
        assertEquals("Regel 110 · Dienstleister-Details", loop.children().get(0).title());
        assertEquals(LogicNode.Kind.SET, loop.children().get(1).kind());
    }

    @Test
    void textblockReferencesAndGroups() throws Exception {
        List<LogicNode> logic = logicOf("<document id='1'><textblock textblockID='200829'/>"
                + "<group description='Formular Diabetes'><groupentry name='HL_X' description='Fragebogen'"
                + " selected='true' optional='true'><textblock textblockID='199966'/></groupentry></group></document>");

        assertEquals(LogicNode.Kind.REF, logic.get(0).kind());
        assertEquals("200829", logic.get(0).title());
        LogicNode group = logic.get(1);
        assertEquals(LogicNode.Kind.GROUP, group.kind());
        LogicNode entry = group.children().get(0);
        assertEquals("HL_X", entry.title());
        assertEquals("Fragebogen (vorausgewählt, optional)", entry.detail());
        assertEquals("199966", entry.children().get(0).title());
    }

    @Test
    void htmlContainsLogicTemplateOnlyOncePerFile() throws Exception {
        Files.writeString(dir.resolve("v.cml"), "<document id='V'><textblock textblockID='B'/><textblock textblockID='B'/></document>");
        Files.writeString(dir.resolve("b.cml"), "<textblock id='B'><set><variable name='X'/><equalTo/>"
                + "<conditionValue>1</conditionValue></set></textblock>");
        ScanOptions options = ScanOptions.defaults();
        List<XmlFile> files = new XmlScanner(options).scanDirectories(List.of(dir));
        TreeBuilder builder = new TreeBuilder(files, options);
        XmlFile start = files.stream().filter(f -> f.displayName().equals("v.cml")).findFirst().orElseThrow();

        String html = new HtmlRenderer(dir, builder::lookup).render(builder.build(start), files.size(), List.of());

        // v.cml enthält nur Verweise (stehen schon im Baum), b.cml Regeln – zweimal referenziert, ein Template
        assertEquals(1, html.split("class=\"logic-tpl\"", -1).length - 1, "ein Template je Datei mit Regeln");
        assertTrue(html.contains("Logik · 1 Zuweisung"));
        assertTrue(html.contains("<td class=\"vr\">X</td>"));
    }
}
