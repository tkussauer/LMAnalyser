package de.xmltree;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eine eingelesene XML-Datei mit den Kennungen ihres Root-Elements und allen
 * Werten, die als Referenz auf eine andere Datei in Frage kommen, sowie ihrer Regel-Logik
 * und Meta-Angaben.
 */
public record XmlFile(Path path, String rootElement, String id, String description,
                      List<Candidate> candidates, List<LogicNode> logic, Map<String, String> meta) {

    public XmlFile {
        meta = Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    /** Wert eines Meta-Eintrags ({@code <meta><generic><entry name="…" val="…"/>}), leer → {@code null}. */
    public String meta(String name) {
        String value = meta.get(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Ein Wert aus der Datei (Attribut oder Elementtext), der eine Referenz sein kann. */
    public record Candidate(String value, String source) {
    }

    public String displayName() {
        return path.getFileName().toString();
    }
}
