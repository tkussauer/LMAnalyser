package de.xmltree;

import java.util.List;

/**
 * Ein Schritt der Regel-Logik eines Bausteins in lesbarer Form, z. B. eine Zuweisung,
 * eine Bedingung oder eine Schleife.
 *
 * @param kind     Art des Schritts
 * @param title    Hauptinhalt, z. B. Zielvariable, Bedingung oder Gruppenname
 * @param detail   Zusatz, z. B. der zugewiesene Wert; {@code null} wenn nicht vorhanden
 * @param children untergeordnete Schritte
 */
public record LogicNode(Kind kind, String title, String detail, List<LogicNode> children) {

    public enum Kind {
        /** Beschriftung aus einem Regel-Kommentar, z. B. „Regel 110 · Dienstleister-Details“. */
        LABEL,
        /** {@code <iteration>}: Schleife über eine Liste. */
        ITERATION,
        /** {@code <conditions>}: WENN … DANN. */
        IF,
        /** {@code <else>}: SONST. */
        ELSE,
        /** {@code <set>}: Zuweisung Variable = Wert. */
        SET,
        /** {@code <textblock textblockID="…"/>}: Verweis auf einen anderen Baustein. */
        REF,
        /** {@code <group>}. */
        GROUP,
        /** {@code <groupentry>}. */
        ENTRY,
        /** Sonstiges Element, generisch dargestellt. */
        ELEMENT,
        /** Freitext. */
        TEXT
    }

    public LogicNode {
        children = List.copyOf(children);
    }

    static LogicNode leaf(Kind kind, String title, String detail) {
        return new LogicNode(kind, title, detail, List.of());
    }

    /** Anzahl der Knoten dieser Art in diesem Teilbaum. */
    public int count(Kind wanted) {
        int n = kind == wanted ? 1 : 0;
        for (LogicNode child : children) {
            n += child.count(wanted);
        }
        return n;
    }

    /** Alle Texte des Teilbaums (für die Suche). */
    void collectText(StringBuilder out) {
        out.append(title).append(' ');
        if (detail != null) {
            out.append(detail).append(' ');
        }
        for (LogicNode child : children) {
            child.collectText(out);
        }
    }
}
