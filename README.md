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
java -jar target/xml-tree-viewer-1.0.0.jar --start samples/vorlagen/anamnese.cml

# Vorlage und referenzierte Dateien liegen in verschiedenen Verzeichnissen
java -jar target/xml-tree-viewer-1.0.0.jar --start vorlagen/angebot.cml --dir bausteine --dir texte

# Start über ID oder Description statt Pfad, eigene Zieldatei
java -jar target/xml-tree-viewer-1.0.0.jar --dir samples --start 200000 --out anamnese.html
```

| Option | Bedeutung |
|---|---|
| `--start <wert>` | Referenzdatei (Pfad) oder ID/Description der Startvorlage (Pflicht) |
| `--dir <verz>` | Verzeichnis, in dem die referenzierten Dateien liegen (rekursiv); mehrfach angebbar. Die Startdatei darf auch außerhalb liegen. Standard: Verzeichnis der Startdatei |
| `--out <datei>` | Ziel-HTML-Datei, Standard `tree.html` |
| `--id-names a,b` | Attribut-/Elementnamen der ID am Root-Element, Standard `id,textblockID` |
| `--desc-names a,b` | Attribut-/Elementnamen der Description, Standard `description` |
| `--ref-names a,b` | Attribute/Elemente mit Verweisen, Standard `textblockID`; `*` = alle Werte prüfen |
| `--ignore-case` | Groß-/Kleinschreibung beim Referenzvergleich ignorieren |
| `--ext a,b` | Dateiendungen der einzulesenden Dateien, Standard `cml` (z. B. `--ext cml,xml`) |

Namen werden ohne Beachtung der Groß-/Kleinschreibung und ohne Namespace-Präfix verglichen
(`ID`, `Id`, `t:id` passen alle auf `id`).

## In IntelliJ IDEA starten

1. *File → Open…* und den Projektordner (mit der `pom.xml`) öffnen – IntelliJ erkennt das Maven-Projekt.
2. Oben rechts die Startkonfiguration **XML Tree Viewer (Beispiel)** wählen (liegt in `.run/`)
   und auf ▶ klicken. Ergebnis: `target/tree.html`.
3. Für eigene Dateien: *Run → Edit Configurations…* → Konfiguration kopieren und unter
   *Program arguments* die eigenen Pfade eintragen, z. B.
   `--start C:\vorlagen\angebot.cml --dir C:\referenzen --out C:\temp\baum.html`.

Alternativ `src/main/java/de/xmltree/Main.java` öffnen, auf den grünen Pfeil neben `main`
klicken und anschließend die Argumente wie in Schritt 3 ergänzen.

## So werden Referenzen aufgelöst

Bausteine verweisen über das Attribut `textblockID` auf andere Bausteine, auch innerhalb von
Gruppen:

```xml
<textblock textblockID="200829"/>
<group description="Formular Diabetes">
  <groupentry name="HL_KO_LE_TXK_GESUNDHEIT_DIABETES" description="Fragebogen Gesundheit Kopf Schwindel">
    <textblock textblockID="199966"/>
  </groupentry>
</group>
```

1. Alle `*.cml`-Dateien (bzw. die per `--ext` angegebenen Endungen) werden eingelesen. Am
   **Root-Element** wird die ID (`id` oder `textblockID`) und die `description` ermittelt –
   als Attribut oder als direktes Kindelement.
2. Daraus entsteht ein Index *ID → Datei* und *Description → Datei*.
3. Ab der Startdatei wird jeder `textblockID`-Wert im Index gesucht (ID vor Description). Ein
   Treffer wird zum Kindknoten und rekursiv weiter aufgelöst. Werte ohne passende Datei
   erscheinen als **nicht gefunden**.
4. Steht ein Verweis in einer Gruppe, zeigt der Knoten den Weg dorthin an, z. B.
   *über group „Formular Diabetes“ › groupentry „Fragebogen …“ › textblock/@textblockID*.

Mit `--ref-names` lassen sich andere Verweis-Attribute angeben; `--ref-names "*"` vergleicht
jeden Attributwert und Elementtext mit allen IDs/Descriptions (für unbekannte Formate).

Besonderheiten im Baum:

- **Zyklus** – die Datei liegt bereits auf dem Pfad zur Wurzel und wird nicht erneut aufgelöst.
- **mehrdeutig** – der Wert passt auf mehrere Dateien; alle werden angezeigt.
- Dateien, die an mehreren Stellen referenziert werden, erscheinen an jeder dieser Stellen.
- Dateien mit ungültigem XML, Dateien ohne ID/Description und doppelte IDs stehen unter *Hinweise*.

Die HTML-Seite ist eigenständig (keine externen Ressourcen), mit Suchfeld sowie Auf-/Zuklappen.
DTDs und externe Entities werden beim Parsen nicht aufgelöst.

## Beispiel

Unter `samples/` liegt eine Vorlage im echten Format mit Gruppe, Zyklus und fehlendem Baustein:

```bash
java -jar target/xml-tree-viewer-1.0.0.jar --start samples/vorlagen/anamnese.cml --dir samples/bausteine
```
