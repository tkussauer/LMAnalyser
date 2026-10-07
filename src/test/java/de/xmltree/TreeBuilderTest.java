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
        XmlFile attr = scanner.scanFile(write("a.xml", "<R ID='1' Description='Eins'/>"));
        XmlFile elem = scanner.scanFile(write("b.xml", "<R><id>2</id><description>Zwei</description></R>"));
        XmlFile descOnly = scanner.scanFile(write("c.xml", "<R description='Drei'/>"));

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
        write("root.xml", "<Vorlage id='V'><a ref='X'/><b>Beschreibung Y</b></Vorlage>");
        write("x.xml", "<Teil id='X'><c ref='Z'/></Teil>");
        write("y.xml", "<Teil description='Beschreibung Y'/>");
        write("z.xml", "<Teil id='Z'/>");
        write("unused.xml", "<Teil id='U'/>");

        TreeNode root = buildFrom("root.xml", ScanOptions.defaults());

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
        write("a.xml", "<R id='A'><x ref='B'/></R>");
        write("b.xml", "<R id='B'><x ref='A'/></R>");

        TreeNode root = buildFrom("a.xml", ScanOptions.defaults());

        TreeNode b = root.children().get(0);
        TreeNode backToA = b.children().get(0);
        assertEquals(TreeNode.Status.CYCLE, backToA.status());
        assertTrue(backToA.children().isEmpty());
    }

    @Test
    void reportsUnresolvedOnlyForConfiguredReferenceNames() throws Exception {
        write("a.xml", "<R id='A'><x ref='B'/><x ref='FEHLT'/><note>Freitext</note></R>");
        write("b.xml", "<R id='B'/>");

        TreeNode generic = buildFrom("a.xml", ScanOptions.defaults());
        assertEquals(1, generic.children().size());

        ScanOptions restricted = new ScanOptions(Set.of("id"), Set.of("description"), Set.of("ref"), false);
        TreeNode root = buildFrom("a.xml", restricted);
        assertEquals(2, root.children().size());
        assertEquals(TreeNode.Status.UNRESOLVED, root.children().get(1).status());
        assertEquals("FEHLT", root.children().get(1).via().value());
    }

    @Test
    void ignoreCaseMatchesDifferentSpelling() throws Exception {
        write("a.xml", "<R id='A'><x ref='teil b'/></R>");
        write("b.xml", "<R description='Teil B'/>");

        assertEquals(0, buildFrom("a.xml", ScanOptions.defaults()).children().size());
        ScanOptions ignoreCase = new ScanOptions(Set.of("id"), Set.of("description"), Set.of(), true);
        assertEquals(1, buildFrom("a.xml", ignoreCase).children().size());
    }

    @Test
    void rendersEscapedHtml() throws Exception {
        write("a.xml", "<R id='A' description='&lt;script&gt;'/>");
        TreeNode root = buildFrom("a.xml", ScanOptions.defaults());

        String html = new HtmlRenderer(dir).render(root, 1, List.of());
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(!html.contains("<script>alert"));
        assertTrue(html.contains("a.xml"));
    }
}
