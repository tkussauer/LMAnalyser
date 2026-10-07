package de.xmltree;

import java.util.ArrayList;
import java.util.List;

/** Ein Knoten im aufgelösten Vorlagenbaum. */
public class TreeNode {

    public enum Status {
        /** Referenz aufgelöst, Kinder wurden weiter verfolgt. */
        OK,
        /** Datei liegt bereits auf dem Pfad zur Wurzel – Zyklus, nicht weiter aufgelöst. */
        CYCLE,
        /** Referenzwert passt auf mehrere Dateien. */
        AMBIGUOUS,
        /** Referenzwert konnte keiner Datei zugeordnet werden. */
        UNRESOLVED
    }

    private final XmlFile file;
    private final XmlFile.Candidate via;
    private final Status status;
    private final List<TreeNode> children = new ArrayList<>();

    TreeNode(XmlFile file, XmlFile.Candidate via, Status status) {
        this.file = file;
        this.via = via;
        this.status = status;
    }

    /** Die referenzierte Datei, {@code null} bei {@link Status#UNRESOLVED}. */
    public XmlFile file() {
        return file;
    }

    /** Der Referenzwert, über den dieser Knoten erreicht wurde, {@code null} für die Wurzel. */
    public XmlFile.Candidate via() {
        return via;
    }

    public Status status() {
        return status;
    }

    public List<TreeNode> children() {
        return children;
    }

    public int size() {
        int n = 1;
        for (TreeNode child : children) {
            n += child.size();
        }
        return n;
    }
}
