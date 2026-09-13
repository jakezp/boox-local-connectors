package local.boox.notesdrive;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Explicitly chosen version, preserving every reviewed branch as ancestry. */
final class ConflictResolution {
    static Revision prepare(RevisionCatalog catalog, String notebook, List<String> reviewedHeads,
            String selected, String device, JSONObject localBranch) throws Exception {
        List<String> current = catalog.heads(notebook);
        DriveClient.require(current.size() > 1 && current.size() <= 64 &&
            new HashSet<>(reviewedHeads).size() == reviewedHeads.size() &&
            new HashSet<>(current).equals(new HashSet<>(reviewedHeads)),
            "Versions changed during review. Refresh and review them again.");
        DriveClient.require(current.contains(selected), "Selected version is not a current head");
        if (localBranch != null) {
            Revision local = Revision.decode(localBranch.getString("revision").getBytes(StandardCharsets.UTF_8));
            DriveClient.require(notebook.equals(local.notebook), "Local notebook identity differs");
            boolean represented = false;
            for (String head : current)
                if (NativeInbox.descendant(catalog, head, local.id())) represented = true;
            DriveClient.require(represented, "Publish the local notebook before resolving its versions");
        }
        return new Revision(notebook, device, catalog.revisions.get(selected).payload, current);
    }

    static String label(RevisionCatalog catalog, String id) {
        Revision revision = catalog.revisions.get(id);
        String title = revision.notebook;
        if (revision.payload == null) title = "Recycle-bin version";
        else try {
            byte[] bytes = catalog.payloads.get(revision.payload);
            if (revision.notebook.startsWith("folder-")) title = FolderRecord.decode(bytes).title;
            else if (revision.notebook.startsWith("boox-"))
                title = new NativeArchive(bytes, revision.notebook.substring(5)).title;
        } catch (Exception ignored) {
            title = "Unrecognized content · " + revision.notebook;
        }
        return title + "\n" + revision.device + " · " + id.substring(0, 12);
    }
}
