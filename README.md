# XML Template Tree Viewer

Liest eine Vielzahl von XML-Dateien (Endung `.cml`) ein, löst ausgehend von einer Referenzdatei (der Vorlage)
die Referenzen zwischen den Dateien auf und stellt den entstehenden Baum als HTML-Seite dar.
Pro Knoten werden das Root-Element, dessen **ID** und **Description** sowie die Datei angezeigt.

## Bauen

Benötigt Java 17+ und Maven. Es gibt keine Laufzeitabhängigkeiten.

```bash
mvn package
```

## Aufruf

```bash
# Startdatei angeben – eingelesen wird deren Verzeichnis (rekursiv)
java -jar target/xml-tree-viewer-1.0.0.jar --start samples/vorlage-angebot.cml

# Verzeichnis(se) explizit, Start über ID oder Description, eigene Zieldatei
java -jar target/xml-tree-viewer-1.0.0.jar --dir samples --start V-100 --out angebot.html
```

| Option | Bedeutung |
|---|---|
| `--start <wert>` | Referenzdatei (Pfad) oder ID/Description der Startvorlage (Pflicht) |
| `--dir <verz>` | Verzeichnis mit XML-Dateien, rekursiv; mehrfach angebbar. Standard: Verzeichnis der Startdatei |
| `--out <datei>` | Ziel-HTML-Datei, Standard `tree.html` |
| `--id-names a,b` | Attribut-/Elementnamen der ID am Root-Element, Standard `id` |
| `--desc-names a,b` | Attribut-/Elementnamen der Description, Standard `description` |
| `--ref-names a,b` | Nur diese Attribute/Elemente als Referenz werten (siehe unten) |
| `--ignore-case` | Groß-/Kleinschreibung beim Referenzvergleich ignorieren |
| `--ext a,b` | Dateiendungen der einzulesenden Dateien, Standard `cml` (z. B. `--ext cml,xml`) |

Namen werden ohne Beachtung der Groß-/Kleinschreibung und ohne Namespace-Präfix verglichen
(`ID`, `Id`, `t:id` passen alle auf `id`).

## So werden Referenzen aufgelöst

1. Alle `*.cml`-Dateien (bzw. die per `--ext` angegebenen Endungen) werden eingelesen. Am **Root-Element** wird die ID und die Description
   ermittelt – entweder als Attribut (`<Vorlage id="V-100" description="…">`) oder als direktes
   Kindelement (`<Vorlage><ID>V-100</ID>…`).
2. Daraus entsteht ein Index *ID → Datei* und *Description → Datei*.
3. Ab der Startdatei werden alle Attributwerte und Elementtexte der Datei mit dem Index verglichen.
   Ein Treffer ist eine Referenz und wird zum Kindknoten. Die ID hat Vorrang vor der Description.
   Danach wird rekursiv weiter aufgelöst.

Ohne `--ref-names` wird *jeder* Wert geprüft, so dass kein bestimmtes Referenzformat
vorausgesetzt wird. Ist bekannt, in welchen Attributen bzw. Elementen die Referenzen stehen
(z. B. `--ref-names ref,Verweis`), werden nur diese ausgewertet; Werte ohne passende Datei
erscheinen dann als **nicht gefunden** im Baum.

Besonderheiten im Baum:

- **Zyklus** – die Datei liegt bereits auf dem Pfad zur Wurzel und wird nicht erneut aufgelöst.
- **mehrdeutig** – der Wert passt auf mehrere Dateien; alle werden angezeigt.
- Dateien, die an mehreren Stellen referenziert werden, erscheinen an jeder dieser Stellen.
- Dateien mit ungültigem XML, Dateien ohne ID/Description und doppelte IDs stehen unter *Hinweise*.

Die HTML-Seite ist eigenständig (keine externen Ressourcen), mit Suchfeld sowie Auf-/Zuklappen.
DTDs und externe Entities werden beim Parsen nicht aufgelöst.

## Beispiel

Unter `samples/` liegt eine kleine Vorlagenstruktur mit Zyklus und fehlender Referenz:

```bash
java -jar target/xml-tree-viewer-1.0.0.jar --dir samples --start V-100 --ref-names ref,Verweis
```
