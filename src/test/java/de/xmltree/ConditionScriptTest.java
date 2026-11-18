package de.xmltree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionScriptTest {

    @TempDir
    Path dir;

    private ScriptGenerator.Rules rules(String xml) throws Exception {
        Path file = dir.resolve("b.cml");
        Files.writeString(file, xml);
        return new ScriptGenerator(id -> List.of()).generate(file);
    }

    private static String cond(String variable, String op, String value) {
        return "<condition><variable name='" + variable + "' datatype='string'/><" + op + "/>"
                + "<conditionValue>" + value + "</conditionValue></condition>";
    }

    private static String set(String variable, String value) {
        return "<set><variable name='" + variable + "' description='Sternchenandruck'/><equalTo/>"
                + (value == null ? "<conditionValue/>" : "<conditionValue>" + value + "</conditionValue>") + "</set>";
    }

    @Test
    void ifElseFromElseConditionComment() throws Exception {
        ScriptGenerator.Rules r = rules("<textblock ID='1'>"
                + "<conditions>" + cond("Data.BusinessData.VT.gewinnverwendungKey_BUZ", "equalTo", "BEIVERR")
                + "<then>" + set("VAR_LE_INT_STERNCHEN", "*") + "</then></conditions>"
                + "<conditions><!-- ELSE CONDITION --><condition negation='true'>"
                + "<variable name='Data.BusinessData.VT.gewinnverwendungKey_BUZ'/><equalTo/><conditionValue>BEIVERR</conditionValue>"
                + "</condition><then>" + set("VAR_LE_INT_STERNCHEN", null) + "</then></conditions></textblock>");

        assertEquals(List.of("""
                // Variable VAR_LE_INT_STERNCHEN – Sternchenandruck
                if (Data.BusinessData.VT.gewinnverwendungKey_BUZ == "BEIVERR") {
                    return "*";
                } else {
                    return "";
                }
                """), r.variableRules());
        assertTrue(r.displayRules().isEmpty());
    }

    @Test
    void dcfContentBecomesDisplayRule() throws Exception {
        ScriptGenerator.Rules r = rules("<textblock ID='1'><conditions><parenthesis>"
                + cond("Data.BusinessData.sofortBeginnend", "notEqualTo", "1") + "<and/>"
                + cond("Data.BusinessData.VT.gewinnverwendungKey_BUZ", "equalTo", "BEIVERR")
                + "</parenthesis><then><!-- DCF content START --><table><tbody><tr><td><p>(\n"
                + "<variable name='Data.BusinessData.Doku.textAnfaenglZw' automatic='true'/>\n Tarifbeitrag)</p></td>"
                + "<td><p>(<variable name='Data.BusinessData.Doku.tarifbeitragGesamt'/> EUR)</p></td></tr></tbody></table>"
                + "<!-- DCF content END --></then></conditions></textblock>");

        assertTrue(r.variableRules().isEmpty());
        assertEquals(List.of("""
                // Anzeige-Regel
                if (Data.BusinessData.sofortBeginnend != "1" && Data.BusinessData.VT.gewinnverwendungKey_BUZ == "BEIVERR") {
                    display(`( ${Data.BusinessData.Doku.textAnfaenglZw} Tarifbeitrag) (${Data.BusinessData.Doku.tarifbeitragGesamt} EUR)`); // <table>
                }
                """), r.displayRules());
    }

    @Test
    void chainsNestingLoopsAndIncludes() throws Exception {
        ScriptGenerator.Rules r = rules("<textblock ID='1'><iteration name='dienstleister'>"
                + "<conditions>" + cond("typ", "equalTo", "Werkstatt") + "<then>" + set("VAR_A", "1")
                + "<conditions>" + cond("x", "equalTo", "2") + "<then>" + set("VAR_B", "X") + "</then></conditions>"
                + "</then></conditions>"
                + "<conditions>" + cond("typ", "equalTo", "Mietwagen") + "<then>" + set("VAR_A", "2")
                + "<group description='Gruppe'><groupentry name='HL_X'><textblock textblockID='4711'/></groupentry></group>"
                + "</then></conditions></iteration></textblock>");

        assertEquals(List.of("""
                // Variable VAR_A – Sternchenandruck
                // für jedes Element aus dienstleister
                if (typ == "Werkstatt") {
                    return "1";
                } else if (typ == "Mietwagen") {
                    return "2";
                }
                """, """
                // Variable VAR_B – Sternchenandruck
                // für jedes Element aus dienstleister
                if (typ == "Werkstatt") {
                    if (x == "2") {
                        return "X";
                    }
                }
                """), r.variableRules());
        assertEquals(List.of("""
                // Anzeige-Regel
                // für jedes Element aus dienstleister
                if (typ == "Mietwagen") {
                    // Gruppe „Gruppe“
                    include("4711"); // nicht gefunden, groupentry HL_X
                }
                """), r.displayRules());
    }

    @Test
    void writesScriptFilePerTemplate() throws Exception {
        Path vorlagen = Files.createDirectories(dir.resolve("Vorlagen"));
        Path bausteine = Files.createDirectories(dir.resolve("Bausteine"));
        Files.writeString(vorlagen.resolve("HL_SBU.cml"), "<document ID='V'><textblock textblockID='B'/></document>");
        Files.writeString(vorlagen.resolve("HL_LEER.cml"), "<document ID='L'/>");
        Files.writeString(bausteine.resolve("b.cml"), "<textblock ID='B' Description='Baustein'><conditions>"
                + cond("a", "equalTo", "1") + "<then>" + set("VAR_X", "j") + "</then></conditions></textblock>");
        Path out = dir.resolve("out");

        assertEquals(0, ConditionScript.run(new String[]{"--start", vorlagen.toString(), "--dir", bausteine.toString(),
                "--out", out.toString()}));

        String script = Files.readString(out.resolve("HL_SBU_Script.js"));
        assertTrue(script.startsWith("// Vorlage: V (HL_SBU.cml)"), script);
        assertTrue(script.contains("1 Variablen-Regeln · 0 Anzeige-Regeln"));
        assertTrue(script.contains("// --- Baustein B – Baustein (b.cml)\n\n// Variable VAR_X"));
        assertTrue(script.contains("// ANZEIGE-REGELN (DCF-Content)\n// " + "=".repeat(76) + "\n\n// keine"));
        assertTrue(Files.readString(out.resolve("HL_LEER_Script.js")).contains("0 Variablen-Regeln · 0 Anzeige-Regeln"));
    }

    @Test
    void outerParenthesesAreRemovedOnlyWhenTheyBelongTogether() {
        assertEquals("a && b", ScriptGenerator.stripOuterParentheses("((a && b))"));
        assertEquals("(a) || (b)", ScriptGenerator.stripOuterParentheses("(a) || (b)"));
        assertEquals("x == \"(\"", ScriptGenerator.stripOuterParentheses("(x == \"(\")"));
    }
}
