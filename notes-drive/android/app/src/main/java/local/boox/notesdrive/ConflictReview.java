package local.boox.notesdrive;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.*;

/** A choice applies one entire version; unselected history remains recoverable. */
final class ConflictReview {
    static void show(Activity activity, DriveSession session) {
        RevisionCatalog snapshot = session.catalog;
        if (snapshot == null) return;
        String account = session.boundAccount(), folder = session.folderId;
        Set<String> names = new TreeSet<>();
        for (Revision revision : snapshot.revisions.values())
            if (snapshot.heads(revision.notebook).size() > 1) names.add(revision.notebook);
        if (names.isEmpty()) {
            new AlertDialog.Builder(activity).setTitle("No conflicting versions")
                .setMessage("The last verified check found one current version per item.")
                .setPositiveButton("OK", null).show();
            return;
        }
        List<String> notebooks = new ArrayList<>(names);
        String[] labels = new String[notebooks.size()];
        for (int i = 0; i < labels.length; i++)
            labels[i] = ConflictResolution.label(snapshot, snapshot.heads(notebooks.get(i)).get(0));
        new AlertDialog.Builder(activity).setTitle("Review conflicting items")
            .setItems(labels, (dialog, index) -> {
                String notebook = notebooks.get(index);
                List<String> heads = new ArrayList<>(snapshot.heads(notebook));
                String[] versions = new String[heads.size()];
                for (int i = 0; i < versions.length; i++)
                    versions[i] = ConflictResolution.label(snapshot, heads.get(i));
                new AlertDialog.Builder(activity).setTitle("Choose the version to use")
                    .setItems(versions, (picker, choice) -> new AlertDialog.Builder(activity)
                        .setTitle("Use this version on synced devices?")
                        .setMessage(versions[choice] + "\n\nThis selects the entire version. It does not combine strokes. " +
                            "All other versions remain in Drive history. A recycle-bin version removes the item " +
                            "from active libraries and can be restored.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Use this version", (confirm, which) ->
                            session.resolveConflict(account, folder, notebook, heads, heads.get(choice))).show())
                    .setNegativeButton("Cancel", null).show();
            }).setNegativeButton("Cancel", null).show();
    }
}
