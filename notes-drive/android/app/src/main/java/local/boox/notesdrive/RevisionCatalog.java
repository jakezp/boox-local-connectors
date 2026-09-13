package local.boox.notesdrive;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A catalog is usable only after every record, ancestry edge and payload verifies. */
final class RevisionCatalog {
    final Map<String, Revision> revisions = new HashMap<>();
    final Map<String, byte[]> payloads;

    RevisionCatalog(Map<String, byte[]> records, Map<String, byte[]> payloads) throws Exception {
        this.payloads = Collections.unmodifiableMap(new HashMap<>(payloads));
        for (Map.Entry<String, byte[]> item : records.entrySet()) {
            Revision revision = Revision.decode(item.getValue());
            DriveClient.require(revision.id().equals(item.getKey()), "Revision hash differs");
            revisions.put(item.getKey(), revision);
        }
        Map<String, Integer> remaining = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Revision> item : revisions.entrySet()) {
            Revision r = item.getValue();
            remaining.put(item.getKey(), r.parents.size());
            if (r.parents.isEmpty()) ready.add(item.getKey());
            if (r.payload != null) {
                byte[] bytes = payloads.get(r.payload);
                DriveClient.require(bytes != null && r.payload.equals(DriveClient.digest("SHA-256", bytes)),
                    "Missing or damaged notebook payload");
            }
            for (String parent : r.parents) {
                Revision ancestor = revisions.get(parent);
                DriveClient.require(ancestor != null && r.notebook.equals(ancestor.notebook),
                    "Missing or foreign revision parent");
                children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(item.getKey());
            }
        }
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.remove();
            visited++;
            for (String child : children.getOrDefault(id, Collections.emptyList())) {
                int count = remaining.get(child) - 1;
                remaining.put(child, count);
                if (count == 0) ready.add(child);
            }
        }
        DriveClient.require(visited == revisions.size(), "Cyclic revision ancestry");
    }

    List<String> heads(String notebook) {
        Set<String> heads = new HashSet<>();
        for (Map.Entry<String, Revision> item : revisions.entrySet())
            if (notebook.equals(item.getValue().notebook)) heads.add(item.getKey());
        for (Revision r : revisions.values()) if (notebook.equals(r.notebook)) heads.removeAll(r.parents);
        List<String> sorted = new ArrayList<>(heads);
        Collections.sort(sorted);
        return sorted;
    }
}
