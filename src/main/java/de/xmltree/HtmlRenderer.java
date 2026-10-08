package de.xmltree;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Erzeugt eine eigenständige HTML-Seite (ohne externe Ressourcen) mit dem Vorlagenbaum. */
public class HtmlRenderer {

    private final Path baseDir;

    /** @param baseDir Pfade werden relativ hierzu angezeigt; {@code null} für absolute Pfade. */
    public HtmlRenderer(Path baseDir) {
        this.baseDir = baseDir == null ? null : baseDir.toAbsolutePath().normalize();
    }

    public String render(TreeNode root, int scannedFiles, List<String> warnings) {
        StringBuilder html = new StringBuilder(16_384);
        html.append("""
                <!doctype html>
                <html lang="de">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Vorlagenbaum</title>
                <style>
                :root {
                  --bg: #fafaf9; --fg: #1c1917; --muted: #78716c; --line: #d6d3d1;
                  --card: #ffffff; --accent: #2563eb; --hl: #fef08a;
                  --ok: #15803d; --cycle: #b45309; --amb: #7c3aed; --unres: #b91c1c;
                }
                @media (prefers-color-scheme: dark) {
                  :root {
                    --bg: #1c1917; --fg: #f5f5f4; --muted: #a8a29e; --line: #44403c;
                    --card: #292524; --accent: #60a5fa; --hl: #854d0e;
                    --ok: #4ade80; --cycle: #fbbf24; --amb: #c4b5fd; --unres: #f87171;
                  }
                }
                * { box-sizing: border-box; }
                body { margin: 0; padding: 24px 16px; background: var(--bg); color: var(--fg);
                       font: 14px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif; }
                main { max-width: 1200px; margin: 0 auto; }
                h1 { font-size: 22px; margin: 0 0 4px; }
                .meta { color: var(--muted); margin-bottom: 16px; }
                .toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; position: sticky;
                           top: 0; background: var(--bg); padding: 8px 0; z-index: 1; }
                .toolbar input { flex: 1 1 240px; padding: 6px 10px; border: 1px solid var(--line);
                                 border-radius: 6px; background: var(--card); color: var(--fg); font: inherit; }
                .toolbar button { padding: 6px 12px; border: 1px solid var(--line); border-radius: 6px;
                                  background: var(--card); color: var(--fg); font: inherit; cursor: pointer; }
                .toolbar button:hover { border-color: var(--accent); }
                ul.tree, ul.tree ul { list-style: none; margin: 0; padding-left: 22px; }
                ul.tree { padding-left: 0; }
                ul.tree ul { border-left: 1px dashed var(--line); margin-left: 7px; }
                li { margin: 3px 0; }
                summary { cursor: pointer; list-style: none; }
                summary::-webkit-details-marker { display: none; }
                summary::before { content: "▸"; display: inline-block; width: 14px; color: var(--muted); }
                details[open] > summary::before { content: "▾"; }
                .leaf::before { content: "•"; display: inline-block; width: 14px; color: var(--muted); }
                .node { display: inline-flex; flex-wrap: wrap; align-items: baseline; gap: 4px 10px;
                        padding: 3px 8px; border: 1px solid var(--line); border-radius: 6px; background: var(--card);
                        max-width: calc(100% - 18px); vertical-align: top; }
                .root-el { font-family: ui-monospace, Consolas, monospace; color: var(--accent); }
                .lbl { color: var(--muted); font-size: 12px; }
                .val { font-weight: 600; }
                .none { color: var(--muted); font-style: italic; font-weight: normal; }
                .file { color: var(--muted); font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
                .via { color: var(--muted); font-size: 12px; }
                .badge { font-size: 11px; padding: 0 6px; border-radius: 999px; border: 1px solid currentColor; }
                .s-CYCLE .badge { color: var(--cycle); }
                .s-AMBIGUOUS .badge { color: var(--amb); }
                .s-UNRESOLVED .badge { color: var(--unres); }
                .s-UNRESOLVED .node { border-style: dashed; }
                .hit > .node, .hit > summary > .node { background: var(--hl); }
                .hidden { display: none; }
                .legend { display: flex; flex-wrap: wrap; gap: 12px; color: var(--muted); font-size: 12px; margin-bottom: 12px; }
                .warnings { margin-top: 24px; border: 1px solid var(--line); border-radius: 6px; padding: 8px 12px;
                            background: var(--card); }
                .warnings li { color: var(--cycle); }
                </style>
                </head>
                <body>
                <main>
                <h1>Vorlagenbaum</h1>
                """);
        html.append("<div class=\"meta\">Start: <strong>").append(esc(label(root.file())))
                .append("</strong> · ").append(scannedFiles).append(" Dateien eingelesen · ")
                .append(root.size()).append(" Knoten · erzeugt ")
                .append(esc(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))))
                .append("</div>\n");
        html.append("""
                <div class="toolbar">
                  <input id="filter" type="search" placeholder="Suchen nach ID, Description, Datei …">
                  <button type="button" id="expand">Alle aufklappen</button>
                  <button type="button" id="collapse">Alle zuklappen</button>
                </div>
                <div class="legend">
                  <span class="s-CYCLE"><span class="badge">Zyklus</span> bereits auf dem Pfad, nicht weiter aufgelöst</span>
                  <span class="s-AMBIGUOUS"><span class="badge">mehrdeutig</span> Wert passt auf mehrere Dateien</span>
                  <span class="s-UNRESOLVED"><span class="badge">nicht gefunden</span> Referenz ohne passende Datei</span>
                </div>
                <ul class="tree">
                """);
        renderNode(root, html);
        html.append("</ul>\n");

        if (!warnings.isEmpty()) {
            html.append("<details class=\"warnings\"><summary>Hinweise (").append(warnings.size())
                    .append(")</summary><ul>\n");
            for (String warning : warnings) {
                html.append("<li>").append(esc(warning)).append("</li>\n");
            }
            html.append("</ul></details>\n");
        }

        html.append("""
                </main>
                <script>
                (function () {
                  var all = function (sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); };
                  document.getElementById('expand').onclick = function () {
                    all('ul.tree details').forEach(function (d) { d.open = true; });
                  };
                  document.getElementById('collapse').onclick = function () {
                    all('ul.tree details').forEach(function (d) { d.open = false; });
                  };
                  var input = document.getElementById('filter');
                  input.addEventListener('input', function () {
                    var q = input.value.trim().toLowerCase();
                    var items = all('ul.tree li');
                    items.forEach(function (li) { li.classList.remove('hit', 'hidden'); });
                    if (!q) return;
                    items.forEach(function (li) { li.classList.add('hidden'); });
                    items.forEach(function (li) {
                      if ((li.getAttribute('data-search') || '').indexOf(q) < 0) return;
                      li.classList.add('hit');
                      // Treffer und alle Vorfahren sichtbar machen und aufklappen
                      for (var el = li; el && !el.classList.contains('tree'); el = el.parentElement) {
                        if (el.tagName === 'LI') el.classList.remove('hidden');
                        if (el.tagName === 'DETAILS' && el !== li.firstElementChild) el.open = true;
                      }
                      // Unterbaum eines Treffers ebenfalls anzeigen
                      li.querySelectorAll('li').forEach(function (c) { c.classList.remove('hidden'); });
                    });
                  });
                })();
                </script>
                </body>
                </html>
                """);
        return html.toString();
    }

    private void renderNode(TreeNode node, StringBuilder html) {
        XmlFile file = node.file();
        String search = file == null
                ? node.via().value()
                : String.join(" ", nz(file.id()), nz(file.description()), file.rootElement(), relative(file.path()));
        html.append("<li class=\"s-").append(node.status()).append("\" data-search=\"")
                .append(esc(search.toLowerCase())).append("\">");

        boolean hasChildren = !node.children().isEmpty();
        if (hasChildren) {
            html.append("<details open><summary>");
        } else {
            html.append("<span class=\"leaf\"></span>");
        }

        html.append("<span class=\"node\">");
        if (file == null) {
            html.append("<span class=\"lbl\">Referenz</span><span class=\"val\">")
                    .append(esc(node.via().value())).append("</span>");
        } else {
            html.append("<span class=\"root-el\">&lt;").append(esc(file.rootElement())).append("&gt;</span>");
            html.append("<span><span class=\"lbl\">ID</span> ").append(value(file.id())).append("</span>");
            html.append("<span><span class=\"lbl\">Description</span> ").append(value(file.description())).append("</span>");
            html.append("<span class=\"file\" title=\"").append(esc(file.path().toAbsolutePath().toString()))
                    .append("\">").append(esc(relative(file.path()))).append("</span>");
        }
        if (node.via() != null) {
            html.append("<span class=\"via\">über ").append(esc(node.via().source())).append("</span>");
        }
        switch (node.status()) {
            case CYCLE -> html.append("<span class=\"badge\">Zyklus</span>");
            case AMBIGUOUS -> html.append("<span class=\"badge\">mehrdeutig</span>");
            case UNRESOLVED -> html.append("<span class=\"badge\">nicht gefunden</span>");
            default -> { }
        }
        html.append("</span>");

        if (hasChildren) {
            html.append("</summary><ul>\n");
            for (TreeNode child : node.children()) {
                renderNode(child, html);
            }
            html.append("</ul></details>");
        }
        html.append("</li>\n");
    }

    private String relative(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        if (baseDir != null && abs.startsWith(baseDir)) {
            return baseDir.relativize(abs).toString().replace('\\', '/');
        }
        // Außerhalb des Basisverzeichnisses nur den Dateinamen zeigen, der volle Pfad steht im Tooltip
        return abs.getFileName().toString();
    }

    private static String label(XmlFile file) {
        if (file.id() != null) {
            return file.id() + (file.description() != null ? " – " + file.description() : "");
        }
        return file.description() != null ? file.description() : file.displayName();
    }

    private static String value(String s) {
        return s == null ? "<span class=\"none\">–</span>" : "<span class=\"val\">" + esc(s) + "</span>";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String esc(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
