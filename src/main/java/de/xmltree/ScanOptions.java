package de.xmltree;

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
 * @param extensions       Dateiendungen der einzulesenden Dateien (ohne Punkt)
 */
public record ScanOptions(Set<String> idNames, Set<String> descriptionNames,
                          Set<String> referenceNames, boolean ignoreCase, Set<String> extensions) {

    public static final Set<String> DEFAULT_EXTENSIONS = Set.of("cml");

    public static ScanOptions defaults() {
        return new ScanOptions(Set.of("id"), Set.of("description"), Set.of(), false);
    }

    public ScanOptions(Set<String> idNames, Set<String> descriptionNames,
                       Set<String> referenceNames, boolean ignoreCase) {
        this(idNames, descriptionNames, referenceNames, ignoreCase, DEFAULT_EXTENSIONS);
    }

    public ScanOptions {
        idNames = lower(idNames);
        descriptionNames = lower(descriptionNames);
        referenceNames = lower(referenceNames);
        extensions = lower(extensions.stream().map(e -> e.trim().startsWith(".") ? e.trim().substring(1) : e).toList());
        if (extensions.isEmpty()) {
            throw new IllegalArgumentException("Mindestens eine Dateiendung angeben");
        }
    }

    /** Prüft, ob der Dateiname eine der konfigurierten Endungen hat. */
    boolean hasExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && extensions.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
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
