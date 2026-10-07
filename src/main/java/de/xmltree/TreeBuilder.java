package de.xmltree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Baut ausgehend von einer Startdatei den Referenzbaum auf. Eine Referenz ist ein Wert in
 * einer Datei, der der ID oder der Description des Root-Elements einer anderen Datei entspricht.
 */
public class TreeBuilder {

    private final ScanOptions options;
    private final Map<String, List<XmlFile>> byId = new LinkedHashMap<>();
    private final Map<String, List<XmlFile>> byDescription = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public TreeBuilder(List<XmlFile> files, ScanOptions options) {
        this.options = options;
        for (XmlFile file : files) {
            if (file.id() != null) {
                byId.computeIfAbsent(options.key(file.id()), k -> new ArrayList<>()).add(file);
            }
            if (file.description() != null) {
                byDescription.computeIfAbsent(options.key(file.description()), k -> new ArrayList<>()).add(file);
            }
            if (file.id() == null && file.description() == null) {
                warnings.add("Weder ID noch Description im Root-Element: " + file.path());
            }
        }
        byId.forEach((key, list) -> {
            if (list.size() > 1) {
                warnings.add("ID '" + key + "' ist mehrfach vergeben: " + paths(list));
            }
        });
    }

    public List<String> warnings() {
        return warnings;
    }

    /** Sucht Dateien, deren ID (bevorzugt) oder Description dem Wert entspricht. */
    public List<XmlFile> lookup(String value) {
        String key = options.key(value);
        List<XmlFile> hits = byId.get(key);
        if (hits == null) {
            hits = byDescription.get(key);
        }
        return hits == null ? List.of() : hits;
    }

    public TreeNode build(XmlFile start) {
        TreeNode root = new TreeNode(start, null, TreeNode.Status.OK);
        Set<XmlFile> path = new HashSet<>();
        path.add(start);
        expand(root, path);
        return root;
    }

    private void expand(TreeNode node, Set<XmlFile> path) {
        XmlFile file = node.file();
        Set<XmlFile> seenTargets = new LinkedHashSet<>();
        Set<String> seenUnresolved = new HashSet<>();

        for (XmlFile.Candidate candidate : file.candidates()) {
            List<XmlFile> targets = lookup(candidate.value()).stream()
                    .filter(target -> target != file)
                    .toList();
            if (targets.isEmpty()) {
                // Ohne explizite Referenznamen ist nicht jeder Wert eine Referenz – nur melden,
                // wenn der Wert aus einem als Referenz konfigurierten Attribut/Element stammt.
                if (options.restrictsReferences() && seenUnresolved.add(options.key(candidate.value()))) {
                    node.children().add(new TreeNode(null, candidate, TreeNode.Status.UNRESOLVED));
                }
                continue;
            }
            for (XmlFile target : targets) {
                if (!seenTargets.add(target)) {
                    continue;
                }
                TreeNode.Status status;
                if (path.contains(target)) {
                    status = TreeNode.Status.CYCLE;
                } else if (targets.size() > 1) {
                    status = TreeNode.Status.AMBIGUOUS;
                } else {
                    status = TreeNode.Status.OK;
                }
                TreeNode child = new TreeNode(target, candidate, status);
                node.children().add(child);
                if (status != TreeNode.Status.CYCLE) {
                    path.add(target);
                    expand(child, path);
                    path.remove(target);
                }
            }
        }
    }

    private static String paths(List<XmlFile> files) {
        return files.stream().map(f -> f.path().toString()).toList().toString();
    }
}
