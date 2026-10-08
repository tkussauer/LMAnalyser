package de.xmltree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeBuilderTest {

    @TempDir
    Path dir;

    private Path write(String name, String xml) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, xml);
        return file;
    }

    private TreeNode buildFrom(String startName, ScanOptions options) {
        XmlScanner scanner = new XmlScanner(options);
        List<XmlFile> files = scanner.scanDirectories(List.of(dir));
        XmlFile start = files.stream()
                .filter(f -> f.displayName().equals(startName))
                .findFirst().orElseThrow();
        return new TreeBuilder(files, options).build(start);
    }

    @Test
    void readsIdAndDescriptionFromAttributesOrChildElements() throws Exception {
        XmlScanner scanner = new XmlScanner(ScanOptions.defaults());
        XmlFile attr = scanner.scanFile(write("a.cml", "<R ID='1' Description='Eins'/>"));
        XmlFile elem = scanner.scanFile(write("b.cml", "<R><id>2</id><description>Zwei</description></R>"));
        XmlFile descOnly = scanner.scanFile(write("c.cml", "<R description='Drei'/>"));

        assertEquals("1", attr.id());
        assertEquals("Eins", attr.description());
        assertEquals("2", elem.id());
        assertEquals("Zwei", elem.description());
        assertNull(descOnly.id());
        assertEquals("Drei", descOnly.description());
        // eigene Kennungen sind keine Referenzkandidaten
        assertTrue(attr.candidates().isEmpty());
        assertTrue(elem.candidates().isEmpty());
    }

    @Test
    void resolvesReferencesByIdAndDescription() throws Exception {
        write("root.cml", "<Vorlage id='V'><a ref='X'/><b>Beschreibung Y</b></Vorlage>");
        write("x.cml", "<Teil id='X'><c ref='Z'/></Teil>");
        write("y.cml", "<Teil description='Beschreibung Y'/>");
        write("z.cml", "<Teil id='Z'/>");
        write("unused.cml", "<Teil id='U'/>");

        TreeNode root = buildFrom("root.cml", ScanOptions.anyValueAsReference());

        assertEquals(2, root.children().size());
        TreeNode x = root.children().get(0);
        assertEquals("X", x.file().id());
        assertEquals("a/@ref", x.via().source());
        assertEquals("Z", x.children().get(0).file().id());
        assertEquals("Beschreibung Y", root.children().get(1).file().description());
        assertEquals(4, root.size());
    }

    @Test
    void stopsAtCycles() throws Exception {
        write("a.cml", "<R id='A'><x ref='B'/></R>");
        write("b.cml", "<R id='B'><x ref='A'/></R>");

        TreeNode root = buildFrom("a.cml", ScanOptions.anyValueAsReference());

        TreeNode b = root.children().get(0);
        TreeNode backToA = b.children().get(0);
        assertEquals(TreeNode.Status.CYCLE, backToA.status());
        assertTrue(backToA.children().isEmpty());
    }

    @Test
    void reportsUnresolvedOnlyForConfiguredReferenceNames() throws Exception {
        write("a.cml", "<R id='A'><x ref='B'/><x ref='FEHLT'/><note>Freitext</note></R>");
        write("b.cml", "<R id='B'/>");

        TreeNode generic = buildFrom("a.cml", ScanOptions.anyValueAsReference());
        assertEquals(1, generic.children().size());

        ScanOptions restricted = new ScanOptions(Set.of("id"), Set.of("description"), Set.of("ref"), false);
        TreeNode root = buildFrom("a.cml", restricted);
        assertEquals(2, root.children().size());
        assertEquals(TreeNode.Status.UNRESOLVED, root.children().get(1).status());
        assertEquals("FEHLT", root.children().get(1).via().value());
    }

    @Test
    void ignoreCaseMatchesDifferentSpelling() throws Exception {
        write("a.cml", "<R id='A'><x ref='teil b'/></R>");
        write("b.cml", "<R description='Teil B'/>");

        assertEquals(0, buildFrom("a.cml", ScanOptions.anyValueAsReference()).children().size());
        ScanOptions ignoreCase = new ScanOptions(Set.of("id"), Set.of("description"), Set.of(), true);
        assertEquals(1, buildFrom("a.cml", ignoreCase).children().size());
    }

    @Test
    void readsOnlyConfiguredExtensions() throws Exception {
        write("a.cml", "<R id='A'><x ref='B'/><x ref='C'/></R>");
        write("b.cml", "<R id='B'/>");
        write("c.xml", "<R id='C'/>");

        assertEquals(1, buildFrom("a.cml", ScanOptions.anyValueAsReference()).children().size());
        ScanOptions both = new ScanOptions(Set.of("id"), Set.of("description"), Set.of(), false, Set.of("cml", ".XML"));
        assertEquals(2, buildFrom("a.cml", both).children().size());
    }

    @Test
    void resolvesTextblockReferencesInsideGroups() throws Exception {
        write("vorlage.cml", "<textblock id='100' description='Vorlage'>"
                + "<textblock textblockID='200829'/>"
                + "<group description='Formular Diabetes'>"
                + "<groupentry name='HL_KO_LE_TXK_GESUNDHEIT_DIABETES' description='Fragebogen Gesundheit'"
                + " selected='true' optional='true'><textblock textblockID='199966'/></groupentry>"
                + "</group>"
                + "<textblock textblockID='198156'/>"
                + "</textblock>");
        write("a.cml", "<textblock id='200829' description='Kopf'/>");
        write("b.cml", "<textblock id='199966' description='Formular Diabetes'/>");
        write("c.cml", "<textblock id='198156' description='Fuß'/>");

        TreeNode root = buildFrom("vorlage.cml", ScanOptions.defaults());

        // Gruppen-Beschreibungen sind keine Verweise, auch wenn sie einer Description entsprechen
        assertEquals(List.of("200829", "199966", "198156"),
                root.children().stream().map(n -> n.file().id()).toList());
        assertEquals("group „Formular Diabetes“ › groupentry „Fragebogen Gesundheit“ › textblock/@textblockID",
                root.children().get(1).via().source());
    }

    @Test
    void reportsMissingTextblock() throws Exception {
        write("vorlage.cml", "<textblock id='1'><textblock textblockID='404'/></textblock>");

        TreeNode root = buildFrom("vorlage.cml", ScanOptions.defaults());

        assertEquals(TreeNode.Status.UNRESOLVED, root.children().get(0).status());
        assertEquals("404", root.children().get(0).via().value());
    }

    @Test
    void rendersEscapedHtml() throws Exception {
        write("a.cml", "<R id='A' description='&lt;script&gt;'/>");
        TreeNode root = buildFrom("a.cml", ScanOptions.anyValueAsReference());

        String html = new HtmlRenderer(dir).render(root, 1, List.of());
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(!html.contains("<script>alert"));
        assertTrue(html.contains("a.cml"));
    }
}
