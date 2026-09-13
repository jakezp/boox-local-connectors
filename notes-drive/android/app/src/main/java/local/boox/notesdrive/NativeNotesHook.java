package local.boox.notesdrive;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Firmware-scoped sync adapter. Native serializer runs on Notes' own single scheduler. */
public final class NativeNotesHook implements IXposedHookLoadPackage {
    private static final String NOTES = "com.onyx.android.note", TAG = "BooxNotesDrive";
    private Context context;
    private ClassLoader loader;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private boolean exporting;
    private volatile boolean enabled;
    private volatile String generation = "";
    private long lastScan;
    private Handler main;
    private NativeApply nativeApply;
    private volatile boolean incoming;
    private volatile boolean replacement;
    private volatile String displayStatus = "Google Drive sync";
    private volatile String incomingRetry = "";
    private final AtomicBoolean applying = new AtomicBoolean();
    private final NativeMetadataApply.Host applyHost = new NativeMetadataApply.Host() {
        @Override public boolean idle() throws Exception {
            for (Object id : ((Map<?, ?>) call(global(), "getDocEditorBundleMap")).keySet())
                if ((Boolean) call(global(), "isOpenedDoc", id)) return false;
            return true;
        }
        @Override public Object model(String id) throws Exception {
            Object active = call(provider(), "loadNote", id);
            Object removed = call(provider(), "loadRemovedNote", id);
            DriveClient.require(active == null || removed == null, "Duplicate native active/recycle-bin identity");
            return active == null ? removed : active;
        }
        @Override public boolean eligible(Object model) throws Exception {
            return model != null && ((Number) call(model, "getType")).intValue() == 1 &&
                NativeNotesHook.this.unlocked(model);
        }
        @Override public boolean unlocked(Object model) throws Exception { return NativeNotesHook.this.unlocked(model); }
        @Override public List<?> models() throws Exception { return (List<?>) call(provider(), "loadAllEnableNoteList"); }
        @Override public boolean captured(Object model) throws Exception {
            return sourceVersion(model).equals(context.getSharedPreferences("boox_drive_capture", 0)
                .getString((String) call(model, "getUniqueId"), ""));
        }
        @Override public byte[] snapshot(Object model) throws Exception { return NativeNotesHook.this.snapshot(model, true); }
        @Override public void acknowledge(org.json.JSONObject offer, byte[] bytes, String title) throws Exception {
            submit(model(offer.getString("notebook").substring(5)), bytes, offer);
        }
        @Override public boolean acknowledged(String notebook, String revision) throws Exception {
            Bundle extras = new Bundle(); extras.putString("notebook", notebook);
            return bridge("applied", revision, extras).getBoolean("applied");
        }
        @Override public void remember(Object model) throws Exception { NativeNotesHook.this.remember(model); }
        @Override public String receipt(Object model) throws Exception { return sourceVersion(model); }
        @Override public void acknowledgeMetadata(org.json.JSONObject offer, byte[] bytes, String title) throws Exception {
            Bundle extras = new Bundle();
            extras.putString("revision", offer.getString("revision"));
            extras.putString("base", offer.getString("base"));
            extras.putBoolean("removed", offer.getBoolean("deleted"));
            extras.putString("title", title);
            if (bytes != null) extras.putString("record", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            bridge("appliedMetadata", null, extras);
        }
    };

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam info) throws Throwable {
        if (!NOTES.equals(info.packageName) || !NOTES.equals(info.processName)) return;
        loader = info.classLoader;
        XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Context candidate = (Context) param.args[0];
                    if (candidate.getPackageManager().getPackageInfo(NOTES, 0).getLongVersionCode() != 45326) return;
                    context = candidate;
                    replacement = context.getSharedPreferences("boox_drive_capture", 0).getBoolean("replacement", false);
                    nativeApply = new NativeApply(context, loader);
                    // Application.attach precedes native database/cache initialization.
                    try { nativeApply.recover(applyHost); }
                    catch (Exception error) { report(error); }
                    HandlerThread bridgeThread = new HandlerThread("NotesDriveBridge");
                    bridgeThread.start();
                    main = new Handler(bridgeThread.getLooper());
                    main.postDelayed(NativeNotesHook.this::poll, 3000);
                    Log.i(TAG, "Native sync adapter v0.4 build " + BuildConfig.HOOK_BUILD + " ready for Notes 45326");
                } catch (Exception error) { report(error); }
            }
        });
        Class<?> manager = type("com.onyx.android.sdk.notecore.editor.NoteManager");
        Class<?> baseModelClass = type("com.raizlabs.android.dbflow.structure.BaseModel");
        Class<?> noteModelClass = type("com.onyx.android.sdk.scribble.data.NoteModel");
        XC_MethodHook gate = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                NativeStore.OPERATION.lock();
                if (nativeApply != null && nativeApply.store.active().exists() && !NativeStore.INTERNAL.get() &&
                        (!baseModelClass.isInstance(param.thisObject) || noteModelClass.isInstance(param.thisObject)))
                    param.setThrowable(new IllegalStateException("Drive is recovering an interrupted notebook update"));
            }
            @Override protected void afterHookedMethod(MethodHookParam param) { NativeStore.OPERATION.unlock(); }
        };
        XposedBridge.hookAllMethods(type("com.onyx.android.sdk.scribble.document.doc.NoteDocument"), "open", gate);
        XposedBridge.hookAllMethods(manager, "saveNoteDocument", gate);
        // Library renames/moves also save NoteModel through DBFlow outside the editor action.
        XposedBridge.hookAllMethods(baseModelClass, "save", gate);
        XposedBridge.hookAllMethods(baseModelClass, "delete", gate);
        XposedBridge.hookAllMethods(manager, "saveNoteDocument", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (context == null || !enabled || exporting || param.getThrowable() != null) return;
                try {
                    Object document = call(param.thisObject, "getNoteDocument");
                    if (!(Boolean) call(document, "isOpen")) return;
                    String id = (String) call(param.thisObject, "getDocumentId");
                    Object model = call(provider(), "loadNote", id);
                    if (eligible(model)) export(model, false);
                } catch (Exception error) { report(error); }
            }
        });
        NativeSyncUi.install(loader, new NativeSyncUi.Host() {
            public boolean replacesOnyx() { return context != null && replacement; }
            public boolean automatic() { return enabled; }
            public String status() { return displayStatus; }
            public Context context() { return context; }
            public void sync() throws Exception {
                lastScan = 0;
                bridge("sync", null, null);
                NativeNotesHook.this.status("Google Drive sync requested. Open edits upload after saving; incoming changes wait until close.");
            }
        });
    }

    private void poll() {
        try {
            Bundle configuration = bridge("config", null, null);
            boolean wasEnabled = enabled;
            enabled = configuration.getBoolean("enabled");
            incoming = configuration.getBoolean("incoming");
            replacement = configuration.getBoolean("replacement");
            displayStatus = configuration.getString("status", "Google Drive sync");
            incomingRetry = configuration.getString("incomingRetry", "");
            context.getSharedPreferences("boox_drive_capture", 0).edit()
                .putBoolean("replacement", replacement).apply();
            generation = configuration.getString("generation", "");
            if (wasEnabled != enabled) Log.i(TAG, "Native bridge configuration: " + (enabled ? "enabled" : "paused"));
            if (enabled && System.currentTimeMillis() - lastScan >= 60000 && scanning.compareAndSet(false, true)) {
                Object global = global();
                Object bundle = call(global, "getGlobalEditorBundle");
                Object scheduler = call(type("com.onyx.android.sdk.notecore.editor.extension.EditorBundlesKt"),
                    "noteScheduler", bundle);
                call(scheduler, "scheduleDirect", (Runnable) () -> {
                    try {
                        // Closed notebooks only: open editors are captured by their successful save.
                        List<?> models = (List<?>) call(provider(), "loadAllEnableNoteList");
                        int captured = 0, skipped = 0;
                        for (Object model : models) {
                            if (!enabled) break;
                            if (((Number) call(model, "getType")).intValue() == 0 && unlocked(model)) {
                                captureFolder(model);
                                continue;
                            }
                            if (!eligible(model)) { skipped++; continue; }
                            String id = (String) call(model, "getUniqueId");
                            if ((Boolean) call(global(), "isOpenedDoc", id)) continue;
                            try { export(model, true); captured++; }
                            catch (Exception error) { report(error); }
                        }
                        org.json.JSONArray managed = new org.json.JSONArray(
                            bridge("managed", null, null).getString("notebooks", "[]"));
                        for (int i = 0; i < managed.length(); i++) {
                            String key = managed.getString(i);
                            Object model = applyHost.model(key.substring(key.startsWith("folder-") ? 7 : 5));
                            if (model == null) {
                                Bundle removed = new Bundle();
                                removed.putString("notebook", key);
                                removed.putString("title", "");
                                bridge("removed", null, removed);
                            } else if (((Number) call(model, "getStatus")).intValue() != 1) captureRemoved(model);
                        }
                        lastScan = System.currentTimeMillis();
                        status("Library scan checked " + captured + " notebooks; " + skipped + " folders or unsupported items skipped.");
                    } catch (Exception error) { report(error); }
                    finally { scanning.set(false); }
                });
            }
            if (enabled && incoming && !scanning.get() && applying.compareAndSet(false, true)) {
                Object scheduler = call(type("com.onyx.android.sdk.notecore.editor.extension.EditorBundlesKt"),
                    "noteScheduler", call(global(), "getGlobalEditorBundle"));
                call(scheduler, "scheduleDirect", (Runnable) () -> {
                    try { receive(); }
                    catch (Exception error) { report(error); }
                    finally { applying.set(false); }
                });
            }
        } catch (Exception error) { enabled = false; report(error); }
        finally { main.postDelayed(this::poll, 15000); }
    }

    private void receive() throws Exception {
        if (!applyHost.idle()) {
            status("Incoming updates wait until the native editor is closed.");
            return;
        }
        if (nativeApply.store.active().exists()) {
            new NativeAccess(loader).stat("com.onyx.android.sdk.scribble.utils.NoteDatabaseUtils",
                "openDocumentDb", "NewShapeDatabase");
            nativeApply.recover(applyHost);
        }
        if (new File(nativeApply.store.root, "validation-paused.json").exists()) {
            status("Disposable crash validation paused incoming sync after recovery.");
            return;
        }
        org.json.JSONArray offers = new org.json.JSONArray(bridge("incoming", null, null).getString("offers", "[]"));
        for (int i = 0; i < offers.length(); i++) {
            org.json.JSONObject offer = offers.getJSONObject(i);
            if (!"ready".equals(offer.getString("status"))) {
                status("Drive needs review: " + offer.getString("status") + " · " + offer.getString("notebook"));
                continue;
            }
            String key = offer.getString("notebook");
            android.content.SharedPreferences failures = context.getSharedPreferences("boox_drive_apply_failures", 0);
            String revisionId = offer.getString("revision");
            if (failures.contains(revisionId) && incomingRetry.equals(failures.getString(revisionId, ""))) {
                status("Incoming update rolled back safely: " + failures.getString(revisionId + ":error", "interrupted update") +
                    ". Use Retry incoming updates in Drive settings.");
                continue;
            }
            String id = key.substring(key.startsWith("folder-") ? 7 : 5);
            Object model = applyHost.model(id);
            if (model != null) {
                if (offer.getString("base").isEmpty()) {
                    status("Existing native identity has no common Drive baseline: " + id);
                    continue;
                }
                String last = context.getSharedPreferences("boox_drive_capture", 0).getString(id, "");
                if (!sourceVersion(model).equals(last)) {
                    if (((Number) call(model, "getStatus")).intValue() == 0) captureRemoved(model);
                    else if (key.startsWith("folder-")) captureFolder(model);
                    else export(model, true);
                    status("Local changes captured before considering incoming revisions.");
                    continue;
                }
            }
            byte[] bytes = null;
            if (!offer.getBoolean("deleted")) {
                Uri uri = Uri.withAppendedPath(Uri.withAppendedPath(NotesBridge.URI, "incoming"),
                    offer.getString("revision"));
                try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
                    bytes = DriveClient.read(input);
                }
            }
            try {
                status("Applying a verified incoming library update…");
                if (offer.getBoolean("deleted") || key.startsWith("folder-"))
                    new NativeMetadataApply(nativeApply).run(offer, bytes, generation, applyHost);
                else nativeApply.apply(offer, bytes, generation, applyHost);
                status("Incoming library update applied and verified: " + id);
            } catch (Exception error) {
                File journal = new File(nativeApply.store.job(revisionId), "journal.json");
                if (journal.exists() && "ROLLED_BACK".equals(NativeStore.read(journal).optString("phase")))
                    failures.edit().putString(revisionId, incomingRetry)
                        .putString(revisionId + ":error", error.getMessage()).commit();
                report(error);
            }
        }
    }

    private boolean eligible(Object model) throws Exception {
        return model != null && ((Number) call(model, "getType")).intValue() == 1 &&
            ((Number) call(model, "getStatus")).intValue() == 1 && unlocked(model);
    }

    private boolean unlocked(Object model) throws Exception {
        boolean eligible = model != null &&
            ((Number) call(model, "getEncryptionType")).intValue() == 0 &&
            !(Boolean) call(model, "hasEncryptedParent") &&
            ((Number) call(model, "getAssociationType")).intValue() == 0;
        if (!eligible) return false;
        java.util.Set<String> parents = new java.util.HashSet<>();
        String parent = (String) call(model, "getParentUniqueId");
        while (parent != null && !parent.isEmpty()) {
            if (parents.size() >= 64 || !parents.add(parent)) return false;
            Object folder = call(provider(), "loadNote", parent);
            if (folder == null || ((Number) call(folder, "getEncryptionType")).intValue() != 0) return false;
            parent = (String) call(folder, "getParentUniqueId");
        }
        return true;
    }

    private void captureFolder(Object model) throws Exception {
        String id = (String) call(model, "getUniqueId");
        String parent = (String) call(model, "getParentUniqueId");
        FolderRecord folder = new FolderRecord(id, parent == null || parent.isEmpty() ? null : parent,
            (String) call(model, "getTitle"));
        Bundle extras = new Bundle();
        extras.putString("record", new String(folder.encode(), java.nio.charset.StandardCharsets.UTF_8));
        bridge("folder", null, extras);
        remember(model);
    }

    private void captureRemoved(Object model) throws Exception {
        String id = (String) call(model, "getUniqueId");
        Bundle extras = new Bundle();
        extras.putString("notebook", (((Number) call(model, "getType")).intValue() == 0 ? "folder-" : "boox-") + id);
        extras.putString("title", (String) call(model, "getTitle"));
        bridge("removed", null, extras);
        remember(model);
    }

    private void export(Object model, boolean remote) throws Exception {
        String id = (String) call(model, "getUniqueId");
        String source = sourceVersion(model);
        android.content.SharedPreferences receipts = context.getSharedPreferences("boox_drive_capture", 0);
        if (source.equals(receipts.getString(id, ""))) return;
        byte[] bytes = snapshot(model, remote);
        Bundle result = submit(model, bytes, null);
        remember(model);
        if (result.getBoolean("changed")) Log.i(TAG, "Native snapshot durably queued: " + id);
    }

    private Bundle submit(Object model, byte[] bytes, org.json.JSONObject offer) throws Exception {
        String transfer = bridge("begin", null, null).getString("id");
        Uri uri = Uri.withAppendedPath(NotesBridge.URI, transfer);
        ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "w");
        try (FileOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
            stream.write(bytes);
            stream.getFD().sync();
        }
        Bundle metadata = new Bundle();
        metadata.putString("nativeId", (String) call(model, "getUniqueId"));
        metadata.putString("title", (String) call(model, "getTitle"));
        metadata.putString("sha256", DriveClient.digest("SHA-256", bytes));
        if (offer != null) {
            metadata.putString("incomingRevision", offer.getString("revision"));
            metadata.putString("base", offer.getString("base"));
        }
        return bridge("commit", transfer, metadata);
    }

    private void remember(Object model) throws Exception {
        if (!context.getSharedPreferences("boox_drive_capture", 0).edit()
                .putString((String) call(model, "getUniqueId"), sourceVersion(model)).commit())
            throw new java.io.IOException("Cannot persist native capture receipt");
    }

    private byte[] snapshot(Object model, boolean remote) throws Exception {
        String id = (String) call(model, "getUniqueId");
        if (!id.matches("[A-Za-z0-9-]{1,100}")) throw new IllegalArgumentException("Unsupported native identity");
        File directory = new File(context.getCacheDir(), "drive-export-" + UUID.randomUUID());
        if (!directory.mkdir()) throw new java.io.IOException("Cannot create native export directory");
        exporting = true;
        try {
            Object args = type("com.onyx.android.sdk.scribble.data.bean.ExportNoteArgs").getConstructor().newInstance();
            call(args, "setDocumentId", id);
            call(args, "setParentUniqueId", call(model, "getParentUniqueId"));
            call(args, "setTitle", id);
            call(args, "setNoteTitle", call(model, "getTitle"));
            call(args, "setUserId", call(model, "getUserId"));
            call(args, "setFormat", 5);
            call(args, "setSingleNoteExport", true);
            call(args, "setRemoteExport", remote);
            call(args, "setExportDirPath", directory.getAbsolutePath());
            Object global = global();
            Object bundle = remote ? call(global, "getGlobalEditorBundle") :
                ((Map<?, ?>) call(global, "getDocEditorBundleMap")).get(id);
            if (bundle == null) throw new java.io.IOException("Native editor has already closed");
            Class<?> actionType = type("com.onyx.android.note.note.action.export.ExportNoteToFileAction");
            Object action = actionType.getConstructor(
                type("com.onyx.android.sdk.notecore.editor.EditorBundle"), args.getClass()).newInstance(bundle, args);
            // Inspected v45326 serializer stages, excluding UI, path override, extra save and cloud callbacks.
            invokePrivate(action, "A");
            invokePrivate(action, "B");
            invokePrivate(action, "o0");
            File output = new File((String) call(args, "getExportPath"));
            if (!directory.equals(output.getParentFile()) || output.length() > DriveClient.MAX_BYTES)
                throw new java.io.IOException("Notebook exceeds the current 4 MiB transfer limit");
            return DriveClient.read(new FileInputStream(output));
        } finally {
            exporting = false;
            deletePrivate(directory);
        }
    }

    private String sourceVersion(Object model) throws Exception {
        org.json.JSONArray fields = new org.json.JSONArray();
        fields.put(generation).put(call(model, "getUniqueId")).put(call(model, "getTitle"))
            .put(call(model, "getParentUniqueId")).put(call(model, "getDigest"))
            .put(call(model, "getCommitId")).put(call(model, "getEncryptionType"))
            .put(call(model, "getStatus"));
        Object updated = call(model, "getUpdatedAt");
        fields.put(updated instanceof java.util.Date ? ((java.util.Date) updated).getTime() : String.valueOf(updated));
        return DriveClient.digest("SHA-256", fields.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Object provider() throws Exception {
        return call(type("com.onyx.android.sdk.scribble.utils.NoteModelUtils"), "getLocalNoteProvider");
    }
    private Object global() throws Exception { return call(type("com.onyx.android.note.note.GlobalNoteBundle"), "getInstance"); }
    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private Bundle bridge(String method, String argument, Bundle extras) throws Exception {
        Bundle result = context.getContentResolver().call(NotesBridge.URI, method, argument, extras);
        if (result == null || result.containsKey("error"))
            throw new java.io.IOException(result == null ? "Native bridge unavailable" : result.getString("error"));
        return result;
    }
    private void status(String message) {
        try { Bundle value = new Bundle(); value.putString("status", message); bridge("status", null, value); }
        catch (Exception ignored) { }
    }
    private void report(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        Log.w(TAG, message);
        status(message);
    }
    private static void invokePrivate(Object object, String name) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(object);
    }
    private static Object call(Object object, String name, Object... args) throws Exception {
        Class<?> owner = object instanceof Class ? (Class<?>) object : object.getClass();
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> type = types[i] == int.class ? Integer.class : types[i] == boolean.class ? Boolean.class : types[i];
                if (args[i] != null && !type.isInstance(args[i])) matches = false;
            }
            if (matches) return method.invoke(object instanceof Class ? null : object, args);
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }
    private static void deletePrivate(File file) {
        File[] children = java.nio.file.Files.isSymbolicLink(file.toPath()) ? null : file.listFiles();
        if (children != null) for (File child : children) deletePrivate(child);
        file.delete();
    }
}
