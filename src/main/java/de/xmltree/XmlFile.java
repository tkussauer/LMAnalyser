package de.xmltree;

import java.nio.file.Path;
import java.util.List;

/**
 * Eine eingelesene XML-Datei mit den Kennungen ihres Root-Elements und allen
 * Werten, die als Referenz auf eine andere Datei in Frage kommen.
 */
public record XmlFile(Path path, String rootElement, String id, String description,
                      List<Candidate> candidates, List<LogicNode> logic) {

    /** Ein Wert aus der Datei (Attribut oder Elementtext), der eine Referenz sein kann. */
    public record Candidate(String value, String source) {
    }

    public String displayName() {
        return path.getFileName().toString();
    }
}
