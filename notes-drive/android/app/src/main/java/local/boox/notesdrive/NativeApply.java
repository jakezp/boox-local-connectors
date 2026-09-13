package local.boox.notesdrive;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;

/** Runs only on Notes' shared scheduler while no editor is open. */
final class NativeApply {
    interface Host {
        boolean idle() throws Exception;
        Object model(String id) throws Exception;
        boolean eligible(Object model) throws Exception;
        boolean captured(Object model) throws Exception;
        byte[] snapshot(Object model) throws Exception;
        void acknowledge(JSONObject offer, byte[] readback, String title) throws Exception;
        boolean acknowledged(String notebook, String revision) throws Exception;
        String receipt(Object model) throws Exception;
        void remember(Object model) throws Exception;
    }
    private static final String DATA = "com.onyx.android.sdk.scribble.data.";
    private static final String PB = DATA + "proto.pb.doc.";
    private static final String DB = "com.onyx.android.sdk.scribble.utils.NoteDatabaseUtils";
    final NativeStore store;
    final NativeAccess nativeApi;
    NativeApply(Context context, ClassLoader loader) throws Exception {
        store = new NativeStore(context);
        nativeApi = new NativeAccess(loader);
    }

    void recover(Host host) throws Exception {
        synchronized (NativeStore.GATE) {
            if (!store.active().exists()) return;
            JSONObject journal = NativeStore.read(store.active());
            String phase = journal.getString("phase");
            if (phase.equals("COMMITTED") || (phase.equals("VERIFIED") &&
                    host.acknowledged(journal.optString("notebook", "boox-" + journal.getString("id")),
                        journal.getString("revision")))) {
                store.finish(journal);
                return;
            }
            store.rollback(journal);
        }
    }

    void apply(JSONObject offer, byte[] bytes, String generation, Host host) throws Exception {
        NativeStore.OPERATION.lock();
        NativeStore.INTERNAL.set(true);
        try { synchronized (NativeStore.GATE) {
            DriveClient.require(host.idle(), "Close the native editor before incoming sync");
            DriveClient.require(!store.active().exists(), "Native recovery is pending");
            String id = offer.getString("notebook").substring(5);
            NativeArchive archive = new NativeArchive(bytes, id);
            DriveClient.require(DriveClient.digest("SHA-256", bytes).equals(offer.getString("payload")),
                "Native incoming payload changed");
            Object existing = host.model(id);
            DriveClient.require(existing == null || host.eligible(existing),
                "Notebook is locked, unsupported or its local baseline is missing");
            DriveClient.require(existing == null || host.captured(existing), "Native notebook changed before apply");
            Object metadata = metadata(archive);
            DriveClient.require(((Number) nativeApi.invoke(metadata, "getEncryptionType")).intValue() == 0 &&
                ((Number) nativeApi.invoke(metadata, "getAssociationType")).intValue() == 0 &&
                ((Number) nativeApi.invoke(metadata, "getType")).intValue() == 1,
                "Unsupported incoming notebook identity or encryption");
            String parent = (String) nativeApi.invoke(metadata, "getParentUniqueId");
            Set<String> ancestors = new HashSet<>();
            while (parent != null && !parent.isEmpty()) {
                DriveClient.require(ancestors.size() < 64 && ancestors.add(parent) && !id.equals(parent),
                    "Incoming notebook hierarchy contains a cycle");
                Object folder = host.model(parent);
                DriveClient.require(folder != null &&
                    ((Number) nativeApi.invoke(folder, "getType")).intValue() == 0 &&
                    ((Number) nativeApi.invoke(folder, "getStatus")).intValue() == 1 &&
                    ((Number) nativeApi.invoke(folder, "getEncryptionType")).intValue() == 0 &&
                    ((Number) nativeApi.invoke(folder, "getAssociationType")).intValue() == 0,
                    "Incoming parent folder must be synchronized and unlocked first");
                parent = (String) nativeApi.invoke(folder, "getParentUniqueId");
            }
            if (existing != null) copyContent(metadata, existing);
            else {
                nativeApi.invoke(metadata, "setId", 0L);
                nativeApi.invoke(metadata, "setUserId", nativeApi.stat(
                    "com.onyx.android.sdk.data.account.utils.OnyxAccountUtils", "getCurUserId"));
            }
            nativeApi.invoke(existing == null ? metadata : existing, "setPageCount", archive.pages.size());
            // Switching databases checkpoints/closes the previous per-document handle.
            nativeApi.stat(DB, "openDocumentDb", id);
            nativeApi.stat(DB, "openDocumentDb", "NewShapeDatabase");
            JSONObject journal = store.prepare(id, offer.getString("revision"), offer.getString("base"),
                generation, archive);
            try {
                store.replaceFiles(journal);
                nativeApi.stat(DB, "openDocumentDb", id);
                Object note = existing == null ? metadata : existing;
                hydrate(archive, note);
                store.checkpoint(journal, "HYDRATED");
                byte[] readback = host.snapshot(host.model(id));
                RevisionQueue.write(new File(store.job(offer.getString("revision")), "candidate-readback.note"), readback);
                archive.verifyReadback(new NativeArchive(readback, id));
                RevisionQueue.write(new File(store.job(offer.getString("revision")), "readback.note"), readback);
                journal.put("nativeReceipt", host.receipt(host.model(id)));
                store.phase(journal, "VERIFIED");
                // Native content is verified before the connector can advance its applied branch.
                host.acknowledge(offer, readback, archive.title);
                store.checkpoint(journal, "ACKNOWLEDGED");
                host.remember(host.model(id));
                store.finish(journal);
            } catch (Exception error) {
                // An acknowledgment may have committed before its reply was lost.
                if ("VERIFIED".equals(journal.optString("phase")) &&
                        host.acknowledged(offer.getString("notebook"), offer.getString("revision"))) {
                    host.remember(host.model(id));
                    store.finish(journal);
                } else {
                    nativeApi.stat(DB, "openDocumentDb", "NewShapeDatabase");
                    store.rollback(journal);
                    throw error;
                }
            } finally { nativeApi.stat(DB, "openDocumentDb", "NewShapeDatabase"); }
        } } finally {
            NativeStore.INTERNAL.set(false);
            NativeStore.OPERATION.unlock();
        }
    }

    private Object metadata(NativeArchive archive) throws Exception {
        Object proto = nativeApi.stat(DATA + "proto.NoteInfoProto$NoteInfoList", "parseFrom", archive.noteInfo);
        List<?> models = (List<?>) nativeApi.stat(DATA + "proto.doc.NoteInfoPBDocument", "fromProtoList", proto);
        for (Object model : models)
            if (archive.id.equals(nativeApi.invoke(model, "getUniqueId"))) return model;
        throw new IllegalArgumentException("Native metadata parser found no notebook");
    }
    private void copyContent(Object from, Object to) throws Exception {
        for (String field : Arrays.asList("Title", "ParentUniqueId", "NotePageInfo", "NoteBackground",
                "PageNameList", "RichTextPageNameList", "RemovePageList", "PageOriginWidth", "PageOriginHeight",
                "Version", "MiniRequiredVersion", "ActiveScene", "ExtraAttributes", "UpdatedAt", "Status"))
            nativeApi.invoke(to, "set" + field, nativeApi.invoke(from, "get" + field));
    }
    private void hydrate(NativeArchive archive, Object note) throws Exception {
        Object provider = nativeApi.stat("com.onyx.android.sdk.scribble.utils.NoteModelUtils",
            "getLocalNoteProvider");
        nativeApi.invoke(provider, "saveNote", note);
        Object context = nativeApi.create(PB + "PBDataContext", archive.id);
        nativeApi.invoke(nativeApi.instance(PB + "DocPBFactory"), "exportToDBFromPB", context);
        // The default PB factory omits tags from its download list; import them explicitly.
        for (Map.Entry<String, byte[]> file : archive.files.entrySet())
            if (file.getKey().startsWith("tag/pb/")) {
                Object proto = nativeApi.stat(DATA + "proto.TagProto$TagList", "parseFrom", file.getValue());
                List<?> tags = (List<?>) nativeApi.stat(DATA + "note.TagModelData", "fromProtoList", proto, archive.id);
                for (Object tag : tags) nativeApi.invoke(provider, "saveTagModel", tag);
            }
        List<Object> models = new ArrayList<>();
        Map<String, String> expected = new TreeMap<>();
        for (String page : archive.pages) for (Map.Entry<String, byte[]> item : archive.shapes.get(page).entrySet()) {
            Object proto = nativeApi.stat(DATA + "proto.NoteShapeDocProto$ShapeInfoProto", "parseFrom", item.getValue());
            Object model = nativeApi.stat(DATA + "NewShapeModel", "fromShapeInfoProto", proto);
            nativeApi.invoke(model, "setDocumentUniqueId", archive.id);
            nativeApi.invoke(model, "setPageUniqueId", page);
            nativeApi.invoke(model, "setPointSaveType", 1);
            nativeApi.invoke(model, "setCoordType", 1);
            byte[] canonical = (byte[]) nativeApi.invoke(nativeApi.invoke(model, "toShapeInfoProto"), "toByteArray");
            expected.put(item.getKey(), NativeArchive.shapeSignature(canonical));
            models.add(model);
        }
        DriveClient.require(models.isEmpty() ||
            (Boolean) nativeApi.stat(DATA + "NewShapeDataProvider", "originSaveShapeList", models),
            "Native shape storage failed");
        for (Map.Entry<String, String> item : expected.entrySet()) {
            Object actual = nativeApi.stat(DATA + "NewShapeDataProvider", "loadNewShapeModel", item.getKey());
            DriveClient.require(actual != null, "Native shape readback is missing");
            byte[] canonical = (byte[]) nativeApi.invoke(nativeApi.invoke(actual, "toShapeInfoProto"), "toByteArray");
            DriveClient.require(item.getValue().equals(NativeArchive.shapeSignature(canonical)),
                "Native database changed stroke semantics");
        }
        for (String page : archive.pages) {
            Object loader = nativeApi.create("com.onyx.android.sdk.data.point.PagePointLoader", page,
                new File(store.point(archive.id), page).getPath());
            Map<?, ?> loaded = (Map<?, ?>) nativeApi.invoke(loader, "loadShapePointList");
            DriveClient.require(loaded.keySet().equals(archive.points.get(page).keySet()),
                "Native point loader did not recover every stroke");
        }
    }
}
