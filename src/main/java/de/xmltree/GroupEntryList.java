package de.xmltree;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fünfte Anwendung: listet je Vorlage alle Blöcke ({@code <groupentry>}) der erreichbaren Bausteine
 * in einer HTML-Seite ({@code <Vorlage>_Bloecke.html}). Ein Button gibt die ausgewählten Blöcke als
 * kommaseparierte Liste aus.
 */
public final class GroupEntryList {

    private static final String USAGE = """
            Aufruf:
              java -cp xml-tree-viewer.jar de.xmltree.GroupEntryList --start <vorlage|verz> [Optionen]

            Optionen:
              --start <wert>      Vorlage (Pfad), ID/Description einer Vorlage oder ein Verzeichnis mit
                                  Vorlagen (rekursiv; je Vorlage eine HTML-Datei) (Pflicht)
              --dir <verz>        Verzeichnis mit den Bausteinen (rekursiv); mehrfach angebbar
              --out <verz>        Zielverzeichnis (Standard: aktuelles Verzeichnis);
                                  Dateiname: <Vorlage>_Bloecke.html
              --id-names, --desc-names, --ref-names, --ext, --ignore-case   wie beim Tree-Viewer
              -h, --help          Diese Hilfe
            """;

    /**
     * Ein Block.
     *
     * @param group       Beschreibung der umgebenden {@code <group>}, sonst leer
     * @param name        {@code name} des groupentry
     * @param description {@code description} des groupentry
     * @param textblocks  eingebundene Bausteine ({@code textblockID})
     * @param selected    {@code selected="true"} (vorausgewählt)
     * @param optional    {@code optional="true"}
     * @param source      Baustein, in dem der Block steht
     */
    record Entry(String group, String name, String description, List<String> textblocks,
                 boolean selected, boolean optional, XmlFile source) {
    }

    private GroupEntryList() {
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
        TemplateSelection selection = TemplateSelection.parse(args, USAGE);
        if (selection == null) {
            return 0;
        }
        TreeBuilder builder = selection.builder();
        DocumentBuilder parser = XmlScanner.newSecureBuilder();
        Map<XmlFile, List<Entry>> cache = new HashMap<>();
        Set<String> usedNames = new HashSet<>();
        int failed = 0;
        for (XmlFile template : selection.templates()) {
            Path target = uniqueName(selection.out().resolve(baseName(template) + "_Bloecke.html"), usedNames);
            try {
                List<Entry> entries = new ArrayList<>();
                for (XmlFile baustein : builder.reachable(template)) {
                    entries.addAll(cache.computeIfAbsent(baustein, f -> collect(parser, f)));
                }
                Path parent = target.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(target, render(template, entries, builder), StandardCharsets.UTF_8);
                System.out.println("  " + template.displayName() + " -> " + target.toAbsolutePath()
                        + " (" + entries.size() + " Blöcke)");
            } catch (IOException | RuntimeException e) {
                failed++;
                System.err.println("  Fehler bei " + template.path() + ": " + e.getMessage());
            }
        }
        System.out.printf("%d Vorlagen verarbeitet (%d fehlgeschlagen).%n", selection.templates().size() - failed, failed);
        return failed == 0 ? 0 : 1;
    }

    /** Alle groupentry-Elemente eines Bausteins in Dokumentreihenfolge. */
    static List<Entry> collect(DocumentBuilder parser, XmlFile file) {
        try {
            Element root = parser.parse(file.path().toFile()).getDocumentElement();
            parser.reset();
            List<Entry> entries = new ArrayList<>();
            walk(root, new ArrayDeque<>(), file, entries);
            return entries;
        } catch (IOException | SAXException e) {
            System.err.println("  Datei nicht lesbar (" + file.path() + "): " + e.getMessage());
            return List.of();
        }
    }

    private static void walk(Element element, Deque<String> groups, XmlFile file, List<Entry> out) {
        String tag = ScanOptions.localName(element.getTagName());
        if (tag.equals("meta")) {
            return;
        }
        boolean isGroup = tag.equals("group");
        if (isGroup) {
            groups.push(element.getAttribute("description").trim());
        }
        if (tag.equals("groupentry")) {
            List<String> ids = new ArrayList<>();
            collectTextblocks(element, ids);
            out.add(new Entry(groups.isEmpty() ? "" : groups.peek(), element.getAttribute("name").trim(),
                    element.getAttribute("description").trim(), ids,
                    "true".equalsIgnoreCase(element.getAttribute("selected")),
                    "true".equalsIgnoreCase(element.getAttribute("optional")), file));
        }
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e) {
                walk(e, groups, file, out);
            }
        }
        if (isGroup) {
            groups.pop();
        }
    }

    /** textblockIDs im groupentry, ohne die eines verschachtelten groupentry. */
    private static void collectTextblocks(Element element, List<String> ids) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e) {
                String tag = ScanOptions.localName(e.getTagName());
                if (tag.equals("groupentry")) {
                    continue;
                }
                String id = e.getAttribute("textblockID").trim();
                if (tag.equals("textblock") && !id.isEmpty() && !ids.contains(id)) {
                    ids.add(id);
                }
                collectTextblocks(e, ids);
            }
        }
    }

    static String render(XmlFile template, List<Entry> entries, TreeBuilder builder) {
        StringBuilder html = new StringBuilder(8192);
        html.append("""
                <!doctype html>
                <html lang="de">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Blöcke</title>
                <style>
                :root { --bg: #fafaf9; --fg: #1c1917; --muted: #78716c; --line: #d6d3d1; --card: #ffffff;
                        --accent: #2563eb; --soft: #f5f5f4; --unres: #b91c1c; }
                @media (prefers-color-scheme: dark) {
                  :root { --bg: #1c1917; --fg: #f5f5f4; --muted: #a8a29e; --line: #44403c; --card: #292524;
                          --accent: #60a5fa; --soft: #1c1917; --unres: #f87171; }
                }
                * { box-sizing: border-box; }
                body { margin: 0; padding: 24px 16px; background: var(--bg); color: var(--fg);
                       font: 14px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif; }
                main { max-width: 1400px; margin: 0 auto; }
                h1 { font-size: 22px; margin: 0 0 4px; }
                .meta { color: var(--muted); margin-bottom: 16px; }
                .bar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 12px; }
                .bar input[type=search] { flex: 1 1 260px; }
                input, select, button, textarea { font: inherit; color: var(--fg); background: var(--card);
                        border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px; }
                button { cursor: pointer; }
                button.primary { background: var(--accent); border-color: var(--accent); color: #fff; font-weight: 600; }
                .result { margin-bottom: 16px; }
                .result textarea { width: 100%; min-height: 70px; font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
                .result .info { color: var(--muted); font-size: 12px; margin-top: 4px; }
                .wrap { overflow-x: auto; }
                table { border-collapse: collapse; width: 100%; background: var(--card); border: 1px solid var(--line); }
                th, td { text-align: left; padding: 5px 8px; border-bottom: 1px solid var(--line); vertical-align: top; }
                th { color: var(--muted); font-weight: 600; font-size: 12px; position: sticky; top: 0; background: var(--card); }
                tbody tr:hover td { background: var(--soft); }
                .code { font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
                .muted { color: var(--muted); }
                .unres { color: var(--unres); }
                td.c { text-align: center; }
                td.small { font-size: 12px; color: var(--muted); min-width: 140px; }
                </style>
                </head>
                <body>
                <main>
                <h1>Blöcke (Groupentries)</h1>
                """);
        long unique = entries.stream().map(Entry::name).filter(n -> !n.isEmpty()).distinct().count();
        html.append("<div class=\"meta\">Vorlage <strong>").append(HtmlRenderer.esc(label(template)))
                .append("</strong> (").append(HtmlRenderer.esc(template.displayName())).append(") · ")
                .append(entries.size()).append(" Blöcke · ").append(unique).append(" verschiedene Namen · erzeugt ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))).append("</div>\n");
        html.append("""
                <div class="bar">
                  <input id="filter" type="search" placeholder="Filtern nach Name, Description, Gruppe, ID …">
                  <button type="button" id="all">Alle auswählen</button>
                  <button type="button" id="none">Keine</button>
                  <label>Liste aus <select id="field">
                    <option value="name">Groupentry-Namen</option>
                    <option value="ids">Baustein-IDs</option>
                  </select></label>
                  <label>Trennzeichen <input id="sep" value="," size="3"></label>
                  <button type="button" class="primary" id="build">Als Liste ausgeben</button>
                </div>
                <div class="result" id="result" hidden>
                  <textarea id="list" readonly></textarea>
                  <div class="info"><button type="button" id="copy">Kopieren</button> <span id="count"></span></div>
                </div>
                <div class="wrap"><table><thead><tr><th><input type="checkbox" id="head" checked title="alle sichtbaren"></th>
                <th>Gruppe</th><th>Groupentry</th><th>Description</th><th>Baustein</th>
                <th>vorausgewählt</th><th>optional</th><th>enthalten in</th></tr></thead><tbody>
                """);
        for (Entry entry : entries) {
            html.append("<tr data-name=\"").append(HtmlRenderer.esc(entry.name()))
                    .append("\" data-ids=\"").append(HtmlRenderer.esc(String.join(",", entry.textblocks()))).append("\">")
                    .append("<td class=\"c\"><input type=\"checkbox\" class=\"pick\" checked></td>")
                    .append("<td class=\"small\">").append(HtmlRenderer.esc(entry.group())).append("</td>")
                    .append("<td class=\"code\">").append(HtmlRenderer.esc(entry.name())).append("</td>")
                    .append("<td>").append(HtmlRenderer.esc(entry.description())).append("</td><td>");
            for (int i = 0; i < entry.textblocks().size(); i++) {
                String id = entry.textblocks().get(i);
                List<XmlFile> targets = builder.lookup(id);
                html.append(i > 0 ? "<br>" : "").append("<span class=\"code\">").append(HtmlRenderer.esc(id)).append("</span>");
                if (targets.isEmpty()) {
                    html.append(" <span class=\"unres\">nicht gefunden</span>");
                } else if (targets.size() == 1 && targets.get(0).description() != null) {
                    html.append(" <span class=\"muted\">").append(HtmlRenderer.esc(targets.get(0).description())).append("</span>");
                }
            }
            html.append("</td><td class=\"c\">").append(entry.selected() ? "ja" : "")
                    .append("</td><td class=\"c\">").append(entry.optional() ? "ja" : "")
                    .append("</td><td class=\"small\">").append(HtmlRenderer.esc(label(entry.source()))).append("</td></tr>\n");
        }
        html.append("""
                </tbody></table></div>
                </main>
                <script>
                (function () {
                  var $ = function (id) { return document.getElementById(id); };
                  var rows = function () { return Array.prototype.slice.call(document.querySelectorAll('tbody tr')); };
                  var visible = function (tr) { return tr.style.display !== 'none'; };
                  $('filter').addEventListener('input', function () {
                    var q = this.value.trim().toLowerCase();
                    rows().forEach(function (tr) {
                      tr.style.display = !q || tr.textContent.toLowerCase().indexOf(q) >= 0 ? '' : 'none';
                    });
                  });
                  var setAll = function (on) {
                    rows().filter(visible).forEach(function (tr) { tr.querySelector('.pick').checked = on; });
                  };
                  $('all').onclick = function () { setAll(true); $('head').checked = true; };
                  $('none').onclick = function () { setAll(false); $('head').checked = false; };
                  $('head').onchange = function () { setAll(this.checked); };
                  // Ausgewählte, sichtbare Blöcke als Liste – jeder Wert nur einmal, in Tabellenreihenfolge
                  $('build').onclick = function () {
                    var field = $('field').value, seen = {}, values = [];
                    rows().filter(visible).forEach(function (tr) {
                      if (!tr.querySelector('.pick').checked) return;
                      (field === 'ids' ? tr.getAttribute('data-ids').split(',') : [tr.getAttribute('data-name')])
                        .forEach(function (v) {
                          if (v && !seen[v]) { seen[v] = true; values.push(v); }
                        });
                    });
                    $('list').value = values.join($('sep').value);
                    $('count').textContent = values.length + (field === 'ids' ? ' Baustein-IDs' : ' Groupentry-Namen');
                    $('result').hidden = false;
                    $('list').select();
                  };
                  $('copy').onclick = function () {
                    var text = $('list').value, done = function () { $('copy').textContent = 'Kopiert ✓'; };
                    if (navigator.clipboard && window.isSecureContext) {
                      navigator.clipboard.writeText(text).then(done, function () { $('list').select(); document.execCommand('copy'); done(); });
                    } else {
                      $('list').select(); document.execCommand('copy'); done();
                    }
                    setTimeout(function () { $('copy').textContent = 'Kopieren'; }, 1500);
                  };
                })();
                </script>
                </body>
                </html>
                """);
        return html.toString();
    }

    private static String label(XmlFile file) {
        String id = file.id() != null ? file.id() : file.displayName();
        return file.description() != null ? id + " – " + file.description() : id;
    }

    private static String baseName(XmlFile file) {
        String name = file.path().getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Path uniqueName(Path target, Set<String> used) {
        String name = target.getFileName().toString();
        String base = name.substring(0, name.length() - ".html".length());
        String candidate = name;
        for (int n = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); n++) {
            candidate = base + "_" + n + ".html";
        }
        return target.resolveSibling(candidate);
    }
}
