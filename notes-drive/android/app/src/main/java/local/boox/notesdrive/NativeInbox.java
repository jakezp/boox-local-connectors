package local.boox.notesdrive;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Verified remote heads eligible for native fast-forward. Clocks never select a winner. */
final class NativeInbox {
    final File directory;
    final String account, folder;
    final RevisionCatalog catalog;
    final LibraryQueue library;

    NativeInbox(File files, String account, String folder) throws Exception {
        this.account = account; this.folder = folder;
        String binding = DriveClient.digest("SHA-256", (account + ":" + folder).getBytes(StandardCharsets.UTF_8));
        directory = new File(files, "incoming/" + binding);
        library = new LibraryQueue(new File(files, "library"));
        File listing = new File(directory, "catalog.json");
        Map<String, byte[]> records = new HashMap<>(), payloads = new HashMap<>();
        if (listing.exists()) {
            JSONObject snapshot = new JSONObject(new String(Files.readAllBytes(listing.toPath()), StandardCharsets.UTF_8));
            LibraryQueue.binding(snapshot, account, folder);
            JSONArray ids = snapshot.getJSONArray("revisions");
            DriveClient.require(ids.length() <= 1000, "Incoming catalog exceeds limit");
            long total = 0;
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.getString(i);
                DriveClient.require(Revision.hash(id), "Invalid incoming revision ID");
                byte[] encoded = Files.readAllBytes(new File(directory, "revision-" + id + ".json").toPath());
                Revision revision = Revision.decode(encoded);
                records.put(id, encoded);
                if (revision.payload != null && !payloads.containsKey(revision.payload)) {
                    byte[] bytes = Files.readAllBytes(new File(directory, "payload-" + revision.payload + ".note").toPath());
                    total += bytes.length;
                    DriveClient.require(bytes.length <= DriveClient.MAX_BYTES && total <= 64L * 1024 * 1024,
                        "Incoming notebook data exceeds limit");
                    payloads.put(revision.payload, bytes);
                }
            }
        }
        catalog = new RevisionCatalog(records, payloads);
    }

    List<JSONObject> offers() throws Exception {
        Set<String> notebooks = new TreeSet<>();
        for (Revision revision : catalog.revisions.values())
            if ((revision.notebook.startsWith("boox-") || revision.notebook.startsWith("folder-")) &&
                    !revision.notebook.equals(DriveSession.VALIDATION_NOTEBOOK))
                notebooks.add(revision.notebook);
        List<JSONObject> result = new ArrayList<>();
        for (String notebook : notebooks) {
            List<String> heads = catalog.heads(notebook);
            if (heads.size() != 1) {
                result.add(new JSONObject().put("notebook", notebook).put("status", "conflict"));
                continue;
            }
            try {
                JSONObject branch = library.branch(account, folder, notebook);
                String base = branch == null ? "" :
                    Revision.decode(branch.getString("revision").getBytes(StandardCharsets.UTF_8)).id();
                String head = heads.get(0);
                if (head.equals(base)) continue;
                if (!base.isEmpty() && !descendant(catalog, head, base)) {
                    result.add(new JSONObject().put("notebook", notebook).put("status", "local-divergence"));
                    continue;
                }
                Revision revision = catalog.revisions.get(head);
                result.add(new JSONObject().put("notebook", notebook).put("revision", head)
                    .put("base", base).put("deleted", revision.payload == null)
                    .put("payload", revision.payload == null ? JSONObject.NULL : revision.payload)
                    .put("status", "ready"));
            } catch (Exception error) {
                result.add(new JSONObject().put("notebook", notebook).put("status", "local-pending"));
            }
        }
        return result;
    }

    JSONObject requireOffer(String id, String base) throws Exception {
        for (JSONObject offer : offers())
            if (id.equals(offer.optString("revision")) && base.equals(offer.optString("base")) &&
                    "ready".equals(offer.optString("status"))) return offer;
        throw new IllegalStateException("Incoming revision is no longer a clean fast-forward");
    }

    static boolean descendant(RevisionCatalog catalog, String head, String base) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(head);
        while (!pending.isEmpty()) {
            String id = pending.remove();
            if (id.equals(base)) return true;
            Revision revision = catalog.revisions.get(id);
            if (revision != null && seen.add(id)) pending.addAll(revision.parents);
        }
        return false;
    }
}
