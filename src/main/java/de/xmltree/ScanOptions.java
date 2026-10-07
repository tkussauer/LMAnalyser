package de.xmltree;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Konfiguration, welche Namen als ID, Description und Referenz gelten.
 *
 * @param idNames          Attribut-/Elementnamen der ID am Root-Element
 * @param descriptionNames Attribut-/Elementnamen der Description am Root-Element
 * @param referenceNames   Attribut-/Elementnamen, die Referenzen enthalten; leer bedeutet:
 *                         jeder Attributwert und jeder Elementtext wird als Referenz geprüft
 * @param ignoreCase       Referenzwerte ohne Beachtung der Groß-/Kleinschreibung vergleichen
 */
public record ScanOptions(Set<String> idNames, Set<String> descriptionNames,
                          Set<String> referenceNames, boolean ignoreCase) {

    public static ScanOptions defaults() {
        return new ScanOptions(lower(List.of("id")), lower(List.of("description")), Set.of(), false);
    }

    public ScanOptions {
        idNames = lower(idNames);
        descriptionNames = lower(descriptionNames);
        referenceNames = lower(referenceNames);
    }

    static Set<String> lower(java.util.Collection<String> names) {
        return names.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean isId(String name) {
        return idNames.contains(localName(name));
    }

    boolean isDescription(String name) {
        return descriptionNames.contains(localName(name));
    }

    boolean restrictsReferences() {
        return !referenceNames.isEmpty();
    }

    boolean isReference(String name) {
        return referenceNames.contains(localName(name));
    }

    String key(String value) {
        String trimmed = value.trim();
        return ignoreCase ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }

    /** Namespace-Präfix entfernen und klein schreiben, z. B. "t:ID" -> "id". */
    static String localName(String name) {
        int colon = name.indexOf(':');
        return (colon >= 0 ? name.substring(colon + 1) : name).toLowerCase(Locale.ROOT);
    }
}
