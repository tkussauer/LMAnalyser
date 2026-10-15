package de.xmltree;

/**
 * Eine in einem Baustein gefundene Variable.
 *
 * @param name      Variablenname (bei {@code <variable>} das Attribut {@code name})
 * @param uname     voll qualifizierter Name ({@code uname}), sonst leer
 * @param iterkey   {@code iterkey}, sonst leer
 * @param datatype  {@code datatype}, sonst leer
 * @param automatic {@code automatic}, sonst leer
 * @param kind      wie die Variable erkannt wurde
 * @param location  Element/Attribut, in dem sie gefunden wurde
 */
public record VariableRef(String name, String uname, String iterkey, String datatype, String automatic,
                          Kind kind, String location) {

    public enum Kind {
        VARIABLE_ELEMENT("variable-Element"),
        MAPPING("Zuordnung name,uname"),
        PATH("Pfad"),
        VAR_NAME("VAR-Name"),
        NAME_LIST("Namensliste");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
