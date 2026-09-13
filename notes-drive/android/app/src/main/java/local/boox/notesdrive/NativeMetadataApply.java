package local.boox.notesdrive;

import java.util.*;
import org.json.JSONObject;

/** Folder updates and recoverable tombstones use the same native open gate and journal. */
final class NativeMetadataApply {
    interface Host extends NativeApply.Host {
        boolean unlocked(Object model) throws Exception;
        List<?> models() throws Exception;
        void acknowledgeMetadata(JSONObject offer, byte[] bytes, String title) throws Exception;
    }
    final NativeApply apply;
    NativeMetadataApply(NativeApply apply) { this.apply = apply; }

    void run(JSONObject offer, byte[] bytes, String generation, Host host) throws Exception {
        NativeStore.OPERATION.lock();
        NativeStore.INTERNAL.set(true);
        try { synchronized (NativeStore.GATE) {
            DriveClient.require(host.idle() && !apply.store.active().exists(), "Close Notes or recover pending update first");
            String notebook = offer.getString("notebook");
            boolean folder = notebook.startsWith("folder-"), removed = offer.getBoolean("deleted");
            String id = notebook.substring(folder ? 7 : 5);
            Object existing = host.model(id);
            DriveClient.require(existing == null || host.unlocked(existing), "Local item is locked or unsupported");
            DriveClient.require(existing == null || host.captured(existing),
                "Local item changed before metadata sync");
            NativeAccess api = apply.nativeApi;
            FolderRecord record = removed ? null : FolderRecord.decode(bytes);
            if (!removed) {
                DriveClient.require(folder && id.equals(record.id), "Folder revision identity differs");
                Set<String> ancestors = new HashSet<>();
                String parent = record.parent;
                while (parent != null && !parent.isEmpty()) {
                    DriveClient.require(ancestors.size() < 64 && ancestors.add(parent) && !id.equals(parent),
                        "Incoming folder hierarchy contains a cycle");
                    Object ancestor = host.model(parent);
                    DriveClient.require(ancestor != null && host.unlocked(ancestor) &&
                        ((Number) api.invoke(ancestor, "getType")).intValue() == 0 &&
                        ((Number) api.invoke(ancestor, "getStatus")).intValue() == 1,
                        "Waiting for an available parent folder");
                    parent = (String) api.invoke(ancestor, "getParentUniqueId");
                }
            }
            if (existing != null)
                DriveClient.require(((Number) api.invoke(existing, "getType")).intValue() == (folder ? 0 : 1),
                    "Native identity has a different type");
            if (removed && folder) {
                for (Object child : host.models())
                    DriveClient.require(!id.equals(api.invoke(child, "getParentUniqueId")),
                        "Waiting for folder children to move or enter the recycle bin");
            }
            JSONObject journal = apply.store.prepare(id, offer.getString("revision"), offer.getString("base"),
                generation, null);
            journal.put("notebook", notebook);
            apply.store.phase(journal, "APPLYING");
            try {
                Object provider = api.stat("com.onyx.android.sdk.scribble.utils.NoteModelUtils", "getLocalNoteProvider");
                Object model = existing;
                if (removed) {
                    if (model != null) api.invoke(provider, "removeNote", model);
                } else {
                    if (model == null) {
                        model = api.create("com.onyx.android.sdk.scribble.data.NoteModel");
                        api.invoke(model, "setUniqueId", id);
                        api.invoke(model, "setUserId", api.stat(
                            "com.onyx.android.sdk.data.account.utils.OnyxAccountUtils", "getCurUserId"));
                        api.invoke(model, "setType", 0);
                        api.invoke(model, "setCreatedAt", new Date());
                    }
                    api.invoke(model, "setTitle", record.title);
                    api.invoke(model, "setParentUniqueId", record.parent);
                    api.invoke(model, "setStatus", 1);
                    api.invoke(model, "setUpdatedAt", new Date());
                    api.invoke(provider, "saveNote", model);
                }
                Object actual = host.model(id);
                if (removed) DriveClient.require(actual == null ||
                    ((Number) api.invoke(actual, "getStatus")).intValue() == 0, "Native recycle-bin readback differs");
                else {
                    DriveClient.require(actual != null, "Native folder is missing");
                    String parent = (String) api.invoke(actual, "getParentUniqueId");
                    byte[] readback = new FolderRecord(id, parent == null || parent.isEmpty() ? null : parent,
                        (String) api.invoke(actual, "getTitle")).encode();
                    DriveClient.require(Arrays.equals(bytes, readback), "Native folder readback differs");
                }
                if (actual != null) journal.put("nativeReceipt", host.receipt(actual));
                apply.store.phase(journal, "VERIFIED");
                host.acknowledgeMetadata(offer, bytes, removed ?
                    (actual == null ? "" : (String) api.invoke(actual, "getTitle")) : record.title);
                if (actual != null) host.remember(actual);
                apply.store.finish(journal);
            } catch (Exception error) {
                if ("VERIFIED".equals(journal.optString("phase")) &&
                        host.acknowledged(notebook, offer.getString("revision"))) apply.store.finish(journal);
                else { apply.store.rollback(journal); throw error; }
            }
        } } finally {
            NativeStore.INTERNAL.set(false);
            NativeStore.OPERATION.unlock();
        }
    }
}
