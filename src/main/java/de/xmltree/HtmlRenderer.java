package de.xmltree;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Erzeugt eine eigenständige HTML-Seite (ohne externe Ressourcen) mit dem Vorlagenbaum. */
public class HtmlRenderer {

    private static final Pattern QUOTED = Pattern.compile("&quot;(.*?)&quot;");
    private static final DateTimeFormatter META_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final Pattern KEYWORD = Pattern.compile("\\b(UND|ODER|NICHT)\\b");

    private final Path baseDir;
    private final Function<String, List<XmlFile>> lookup;
    /** Dateien mit Regel-Logik und die Nummer ihres Templates in der Seite. */
    private final Map<XmlFile, Integer> logicIds = new LinkedHashMap<>();
    /** Stichtag für die Prüfung von validFrom/validTo. */
    private LocalDate today = LocalDate.now();

    /** @param baseDir Pfade werden relativ hierzu angezeigt; {@code null} für absolute Pfade. */
    public HtmlRenderer(Path baseDir) {
        this(baseDir, value -> List.of());
    }

    /**
     * @param lookup findet zu einer ID die Datei(en), um Verweise in der Logik mit ihrer
     *               Description anzuzeigen
     */
    public HtmlRenderer(Path baseDir, Function<String, List<XmlFile>> lookup) {
        this.baseDir = baseDir == null ? null : baseDir.toAbsolutePath().normalize();
        this.lookup = lookup;
    }

    /** Stichtag für die Gültigkeitsprüfung festlegen (Standard: heute). */
    public HtmlRenderer today(LocalDate date) {
        this.today = date;
        return this;
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
                  --kw: #9333ea; --str: #b45309; --var: #0f766e; --soft: #f5f5f4;
                }
                @media (prefers-color-scheme: dark) {
                  :root {
                    --bg: #1c1917; --fg: #f5f5f4; --muted: #a8a29e; --line: #44403c;
                    --card: #292524; --accent: #60a5fa; --hl: #854d0e;
                    --ok: #4ade80; --cycle: #fbbf24; --amb: #c4b5fd; --unres: #f87171;
                    --kw: #d8b4fe; --str: #fcd34d; --var: #5eead4; --soft: #1c1917;
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
                .badge.cond { color: var(--kw); }
                .badge.expired { color: var(--unres); }
                .node .fmeta { flex-basis: 100%; color: var(--muted); font-size: 12px; cursor: help; }
                .node .fmeta + .via { flex-basis: 100%; }
                .s-AMBIGUOUS .badge { color: var(--amb); }
                .s-UNRESOLVED .badge { color: var(--unres); }
                .s-UNRESOLVED .node { border-style: dashed; }
                .hit > .node, .hit > summary > .node { background: var(--hl); }
                .hidden { display: none; }
                .legend { display: flex; flex-wrap: wrap; gap: 12px; color: var(--muted); font-size: 12px; margin-bottom: 12px; }
                .logic { margin: 2px 0 6px 18px; }
                li > .logic { margin-left: 32px; }
                .logic > summary { color: var(--accent); font-size: 12px; }
                .logic > summary::before { width: 12px; }
                .logic-body { margin: 4px 0 0 4px; padding: 8px 10px; border: 1px solid var(--line);
                              border-radius: 6px; background: var(--card); overflow-x: auto; }
                ul.lt { list-style: none; margin: 0; padding-left: 16px; border-left: 1px solid var(--line); }
                .logic-body > ul.lt { padding-left: 0; border-left: none; }
                ul.lt li { margin: 2px 0; }
                .code { font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
                .kw { color: var(--kw); font-weight: 700; font-size: 11px; letter-spacing: .04em; margin-right: 4px; }
                .str { color: var(--str); }
                .vr { color: var(--var); }
                .lbl-row { margin-top: 8px; font-weight: 600; }
                .lbl-row .sub { font-weight: normal; color: var(--muted); font-size: 12px; margin-left: 6px; }
                .txt { color: var(--muted); font-style: italic; }
                table.sets { border-collapse: collapse; margin: 2px 0; }
                table.sets td { padding: 1px 8px 1px 0; vertical-align: top;
                                font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
                table.sets td.eq { color: var(--muted); }
                table.sets tr:hover td { background: var(--soft); }
                .lhit { background: var(--hl); }
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
                  <input id="filter" type="search" placeholder="Suchen nach ID, Description, Datei oder Variable …">
                  <button type="button" id="expand">Alle aufklappen</button>
                  <button type="button" id="collapse">Alle zuklappen</button>
                </div>
                <div class="legend">
                  <span class="s-CYCLE"><span class="badge">Zyklus</span> bereits auf dem Pfad, nicht weiter aufgelöst</span>
                  <span class="s-AMBIGUOUS"><span class="badge">mehrdeutig</span> Wert passt auf mehrere Dateien</span>
                  <span class="s-UNRESOLVED"><span class="badge">nicht gefunden</span> Referenz ohne passende Datei</span>
                  <span><span class="badge cond">bedingt</span> nur unter einer WENN-Bedingung eingebunden</span>
                </div>
                <ul class="tree">
                """);
        renderNode(root, html);
        html.append("</ul>\n");
        for (Map.Entry<XmlFile, Integer> entry : logicIds.entrySet()) {
            StringBuilder text = new StringBuilder();
            entry.getKey().logic().forEach(n -> n.collectText(text));
            html.append("<template class=\"logic-tpl\" id=\"logic-").append(entry.getValue())
                    .append("\" data-text=\"").append(esc(text.toString().toLowerCase())).append("\">");
            renderLogic(entry.getKey().logic(), html);
            html.append("</template>\n");
        }

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
                    all('details.t').forEach(function (d) { d.open = true; });
                  };
                  document.getElementById('collapse').onclick = function () {
                    all('details.t').forEach(function (d) { d.open = false; });
                  };
                  // Logik wird erst beim Aufklappen aus dem Template eingefügt
                  var fill = function (d) {
                    var body = d.querySelector('.logic-body');
                    if (body.childElementCount) return;
                    var tpl = document.getElementById('logic-' + d.getAttribute('data-logic'));
                    if (tpl) body.appendChild(tpl.content.cloneNode(true));
                  };
                  all('details.logic').forEach(function (d) {
                    d.addEventListener('toggle', function () { if (d.open) fill(d); });
                  });
                  var logicText = {};
                  all('template.logic-tpl').forEach(function (t) {
                    logicText[t.id.substring(6)] = t.getAttribute('data-text') || '';
                  });
                  var input = document.getElementById('filter');
                  input.addEventListener('input', function () {
                    var q = input.value.trim().toLowerCase();
                    var items = all('ul.tree li');
                    items.forEach(function (li) { li.classList.remove('hit', 'hidden'); });
                    all('.lhit').forEach(function (e) { e.classList.remove('lhit'); });
                    if (!q) return;
                    items.forEach(function (li) { li.classList.add('hidden'); });
                    items.forEach(function (li) {
                      var inNode = (li.getAttribute('data-search') || '').indexOf(q) >= 0;
                      var inLogic = (logicText[li.getAttribute('data-logic')] || '').indexOf(q) >= 0;
                      if (!inNode && !inLogic) return;
                      li.classList.add('hit');
                      if (inLogic) {
                        // Logik aufklappen und passende Zeilen markieren
                        var d = li.querySelector(':scope > .logic, :scope > details.t > .logic');
                        if (d) {
                          fill(d);
                          d.open = true;
                          d.querySelectorAll('.lr').forEach(function (r) {
                            if (r.textContent.toLowerCase().indexOf(q) >= 0) r.classList.add('lhit');
                          });
                        }
                      }
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
                : String.join(" ", nz(file.id()), nz(file.description()), file.rootElement(), relative(file.path()),
                        String.join(" ", file.meta().values()));
        Integer logicId = null;
        if (file != null && hasRules(file)) {
            logicId = logicIds.computeIfAbsent(file, f -> logicIds.size());
        }
        html.append("<li class=\"s-").append(node.status()).append("\" data-search=\"")
                .append(esc(search.toLowerCase())).append('"');
        if (logicId != null) {
            html.append(" data-logic=\"").append(logicId).append('"');
        }
        html.append('>');

        boolean hasChildren = !node.children().isEmpty();
        if (hasChildren) {
            html.append("<details class=\"t\" open><summary>");
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
            renderMeta(file, html);
        }
        if (node.via() != null) {
            html.append("<span class=\"via\">über ").append(via(node.via().source())).append("</span>");
            if (node.via().source().contains(XmlScanner.CONDITION_PREFIX)) {
                html.append("<span class=\"badge cond\" title=\"Wird nur unter einer Bedingung eingebunden\">bedingt</span>");
            }
        }
        switch (node.status()) {
            case CYCLE -> html.append("<span class=\"badge\">Zyklus</span>");
            case AMBIGUOUS -> html.append("<span class=\"badge\">mehrdeutig</span>");
            case UNRESOLVED -> html.append("<span class=\"badge\">nicht gefunden</span>");
            default -> { }
        }
        html.append("</span>");

        if (hasChildren) {
            html.append("</summary>");
        }
        if (logicId != null) {
            renderLogicToggle(file, logicId, html);
        }
        if (hasChildren) {
            html.append("<ul>\n");
            for (TreeNode child : node.children()) {
                renderNode(child, html);
            }
            html.append("</ul></details>");
        }
        html.append("</li>\n");
    }

    /**
     * Zeile mit Gültigkeit, Eigentümer, Release und letzter Änderung; alle Meta-Einträge im Tooltip.
     * Abgelaufene oder noch nicht gültige Bausteine werden gekennzeichnet.
     */
    private void renderMeta(XmlFile file, StringBuilder html) {
        if (file.meta().isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        String from = file.meta("validFrom");
        String to = file.meta("validTo");
        if (from != null || to != null) {
            parts.add("gültig " + (from != null ? from : "…") + " bis " + (to != null ? to : "…"));
        }
        if (file.meta("owner") != null) {
            parts.add("Eigentümer " + file.meta("owner"));
        }
        if (file.meta("releaseName") != null) {
            parts.add("Release " + file.meta("releaseName"));
        }
        if (file.meta("modificationDate") != null) {
            parts.add("geändert " + file.meta("modificationDate")
                    + (file.meta("modificationUser") != null ? " von " + file.meta("modificationUser") : ""));
        }
        StringBuilder tooltip = new StringBuilder();
        file.meta().forEach((name, value) -> {
            if (!value.isBlank()) {
                tooltip.append(name).append(": ").append(value.trim()).append('\n');
            }
        });
        html.append("<span class=\"fmeta\" title=\"").append(esc(tooltip.toString().trim())).append("\">")
                .append(esc(parts.isEmpty() ? "Meta-Angaben" : String.join(" · ", parts)));
        LocalDate validFrom = parseDate(from);
        LocalDate validTo = parseDate(to);
        if (validTo != null && validTo.isBefore(today)) {
            html.append(" <span class=\"badge expired\">abgelaufen</span>");
        } else if (validFrom != null && validFrom.isAfter(today)) {
            html.append(" <span class=\"badge expired\">noch nicht gültig</span>");
        }
        html.append("</span>");
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value, META_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Nur Verweise und Gruppen zeigt schon der Baum – Logik lohnt sich erst bei Regeln, Text o. Ä. */
    private static boolean hasRules(XmlFile file) {
        return file.logic().stream().anyMatch(HtmlRenderer::hasRules);
    }

    private static boolean hasRules(LogicNode node) {
        return switch (node.kind()) {
            case REF, GROUP, ENTRY -> node.children().stream().anyMatch(HtmlRenderer::hasRules);
            default -> true;
        };
    }

    private static void renderLogicToggle(XmlFile file, int logicId, StringBuilder html) {
        int sets = 0;
        int conditions = 0;
        int loops = 0;
        int refs = 0;
        for (LogicNode node : file.logic()) {
            sets += node.count(LogicNode.Kind.SET);
            conditions += node.count(LogicNode.Kind.IF);
            loops += node.count(LogicNode.Kind.ITERATION);
            refs += node.count(LogicNode.Kind.REF);
        }
        StringBuilder summary = new StringBuilder("Logik");
        appendCount(summary, sets, "Zuweisung", "Zuweisungen");
        appendCount(summary, conditions, "Bedingung", "Bedingungen");
        appendCount(summary, loops, "Schleife", "Schleifen");
        appendCount(summary, refs, "Verweis", "Verweise");
        html.append("<details class=\"logic\" data-logic=\"").append(logicId).append("\"><summary>")
                .append(esc(summary.toString())).append("</summary><div class=\"logic-body\"></div></details>");
    }

    private static void appendCount(StringBuilder out, int count, String one, String many) {
        if (count > 0) {
            out.append(" · ").append(count).append(' ').append(count == 1 ? one : many);
        }
    }

    /** Schreibt die Regel-Logik als verschachtelte Liste; aufeinanderfolgende Zuweisungen als Tabelle. */
    private void renderLogic(List<LogicNode> nodes, StringBuilder html) {
        html.append("<ul class=\"lt\">");
        int i = 0;
        while (i < nodes.size()) {
            if (nodes.get(i).kind() == LogicNode.Kind.SET) {
                html.append("<li><table class=\"sets\">");
                while (i < nodes.size() && nodes.get(i).kind() == LogicNode.Kind.SET) {
                    LogicNode set = nodes.get(i++);
                    html.append("<tr class=\"lr\"><td class=\"vr\">").append(esc(set.title())).append("</td>");
                    if (set.detail() != null) {
                        html.append("<td class=\"eq\">=</td><td>").append(expression(set.detail())).append("</td>");
                    }
                    html.append("</tr>");
                }
                html.append("</table></li>");
                continue;
            }
            renderLogicNode(nodes.get(i++), html);
        }
        html.append("</ul>");
    }

    private void renderLogicNode(LogicNode node, StringBuilder html) {
        String line;
        switch (node.kind()) {
            case LABEL -> {
                html.append("<li class=\"lr lbl-row\">").append(esc(node.title()));
                if (node.detail() != null) {
                    html.append("<span class=\"sub\">").append(esc(node.detail())).append("</span>");
                }
                html.append("</li>");
                return;
            }
            case TEXT -> {
                html.append("<li class=\"lr txt\">").append(esc(node.title())).append("</li>");
                return;
            }
            case REF -> {
                List<XmlFile> targets = lookup.apply(node.title());
                html.append("<li class=\"lr\"><span class=\"kw\">BAUSTEIN</span><b>").append(esc(node.title())).append("</b>");
                if (targets.size() == 1 && targets.get(0).description() != null) {
                    html.append(" – ").append(esc(targets.get(0).description()));
                } else if (targets.isEmpty()) {
                    html.append(" <span class=\"s-UNRESOLVED\"><span class=\"badge\">nicht gefunden</span></span>");
                }
                html.append("</li>");
                return;
            }
            case IF -> line = "<span class=\"kw\">WENN</span><span class=\"code\">" + expression(node.title())
                    + "</span> <span class=\"kw\">DANN</span>";
            case ELSE -> line = "<span class=\"kw\">SONST</span>";
            case ITERATION -> line = "<span class=\"kw\">FÜR JEDES ELEMENT AUS</span><span class=\"code vr\">"
                    + esc(node.title()) + "</span>" + (node.detail() != null ? " – " + esc(node.detail()) : "");
            case GROUP -> line = "<span class=\"kw\">GRUPPE</span><b>" + esc(node.title()) + "</b>";
            case ENTRY -> line = "<span class=\"kw\">EINTRAG</span><span class=\"code\">" + esc(node.title())
                    + "</span>" + (node.detail() == null ? ""
                    : (node.detail().startsWith("(") ? " " : " – ") + esc(node.detail()));
            default -> line = "<span class=\"code\">" + esc(node.title()) + "</span>"
                    + (node.detail() != null ? " " + esc(node.detail()) : "");
        }
        if (node.children().isEmpty()) {
            html.append("<li class=\"lr\">").append(line).append("</li>");
            return;
        }
        html.append("<li><details open><summary class=\"lr\">").append(line).append("</summary>");
        renderLogic(node.children(), html);
        html.append("</details></li>");
    }

    /** Verweisweg mit hervorgehobenen Bedingungen, z. B. „WENN a = "1" › group „X“ › textblock/@textblockID“. */
    private static String via(String source) {
        StringBuilder out = new StringBuilder();
        for (String part : source.split(" › ")) {
            if (out.length() > 0) {
                out.append(" › ");
            }
            if (part.startsWith(XmlScanner.CONDITION_PREFIX)) {
                out.append("<span class=\"kw\">WENN</span><span class=\"code\">")
                        .append(expression(part.substring(XmlScanner.CONDITION_PREFIX.length()))).append("</span>");
            } else if (part.startsWith("FÜR JEDES ")) {
                out.append("<span class=\"kw\">FÜR JEDES</span><span class=\"code vr\">")
                        .append(esc(part.substring("FÜR JEDES ".length()))).append("</span>");
            } else {
                out.append(esc(part));
            }
        }
        return out.toString();
    }

    /** Hebt in einem Ausdruck Werte und UND/ODER/NICHT hervor; leere Werte werden als „leer“ angezeigt. */
    private static String expression(String text) {
        String escaped = esc(text);
        Matcher quoted = QUOTED.matcher(escaped);
        StringBuilder out = new StringBuilder();
        while (quoted.find()) {
            String value = quoted.group(1);
            quoted.appendReplacement(out, Matcher.quoteReplacement(value.isEmpty()
                    ? "<span class=\"str\">leer</span>"
                    : "<span class=\"str\">&quot;" + value + "&quot;</span>"));
        }
        quoted.appendTail(out);
        return KEYWORD.matcher(out.toString()).replaceAll("<span class=\"kw\">$1</span>");
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
