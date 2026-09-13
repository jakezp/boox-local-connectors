package local.boox.notesdrive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Only the inspected, installed Notes package can submit native snapshots. No grant leaves this app. */
public final class NotesBridge extends ContentProvider {
    static final Uri URI = Uri.parse("content://local.boox.notesdrive.native");
    private final Map<String, Stage> stages = new HashMap<>();
    private static final class Stage {
        File file;
        String account, folder, generation;
        long created = android.os.SystemClock.elapsedRealtime();
        boolean opened;
    }
    @Override public boolean onCreate() { return true; }

    private void authorize() {
        try {
            int uid = Binder.getCallingUid();
            PackageInfo info = getContext().getPackageManager().getPackageInfo("com.onyx.android.note", 0);
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (uid == info.applicationInfo.uid && info.getLongVersionCode() == 45326 &&
                packages != null && packages.length == 1 && "com.onyx.android.note".equals(packages[0])) return;
        } catch (Exception ignored) { }
        throw new SecurityException("This bridge accepts only the supported BOOX Notes application");
    }

    @Override public synchronized Bundle call(String method, String argument, Bundle extras) {
        // The inspected launcher shares Android's system UID. That UID receives only
        // the settings operation (two booleans / a switch), never the notebook bridge.
        if (!"syncSettings".equals(method) || !settingsSystemCaller()) authorize();
        SharedPreferences prefs = getContext().getSharedPreferences("drive", 0);
        Bundle result = new Bundle();
        try {
            if ("syncSettings".equals(method)) {
                DriveSession session = (DriveSession) getContext().getApplicationContext();
                return session.nativeSyncSettings(extras);
            }
            if ("config".equals(method)) {
                prefs.edit().putLong("hookSeen", System.currentTimeMillis()).apply();
                result.putBoolean("enabled", prefs.getBoolean("automatic", false));
                result.putString("generation", prefs.getString("autoGeneration", ""));
                result.putBoolean("incoming", prefs.getBoolean("automaticIncoming", false));
                result.putBoolean("replacement", prefs.getBoolean("replaceOnyx", prefs.getBoolean("automatic", false)));
                result.putString("status", prefs.getString("autoStatus", "Google Drive sync") + "\n\n" +
                    prefs.getString("nativeStatus", "Open Notes to check the library."));
                result.putString("incomingRetry", prefs.getString("incomingRetry", ""));
                return result;
            }
            if ("applied".equals(method)) {
                result.putBoolean("applied", new LibraryQueue(new File(getContext().getFilesDir(), "library"))
                    .isApplied(extras.getString("notebook"), argument));
                return result;
            }
            if ("status".equals(method)) {
                String status = extras.getString("status", "");
                prefs.edit().putString("nativeStatus", status.substring(0, Math.min(400, status.length()))).apply();
                return result;
            }
            DriveClient.require(prefs.getBoolean("automatic", false), "Automatic publishing is paused");
            String account = prefs.getString("account", ""), folder = prefs.getString("folder", "");
            String generation = prefs.getString("autoGeneration", "");
            DriveClient.require(!account.isEmpty() && !folder.isEmpty() && !generation.isEmpty(),
                "Choose a Drive account and folder first");
            if ("sync".equals(method)) {
                ((DriveSession) getContext().getApplicationContext()).requestAutomaticSync();
                return result;
            }
            if ("managed".equals(method)) {
                result.putString("notebooks", new org.json.JSONArray(new LibraryQueue(
                    new File(getContext().getFilesDir(), "library")).managed()).toString());
                return result;
            }
            if ("folder".equals(method) || "removed".equals(method)) {
                LibraryQueue library = new LibraryQueue(new File(getContext().getFilesDir(), "library"));
                boolean changed;
                if ("folder".equals(method)) {
                    byte[] bytes = extras.getString("record").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    FolderRecord record = FolderRecord.decode(bytes);
                    changed = library.captureRecord(account, folder, "folder-" + record.id, record.title, bytes);
                } else {
                    String notebook = extras.getString("notebook");
                    changed = library.managed().contains(notebook) &&
                        library.captureRecord(account, folder, notebook, extras.getString("title", ""), null);
                }
                if (changed) ((DriveSession) getContext().getApplicationContext()).requestAutomaticSync();
                result.putBoolean("changed", changed);
                return result;
            }
            if ("appliedMetadata".equals(method)) {
                DriveClient.require(prefs.getBoolean("automaticIncoming", false), "Incoming sync is paused");
                String revisionId = extras.getString("revision"), base = extras.getString("base", "");
                NativeInbox inbox = new NativeInbox(getContext().getFilesDir(), account, folder);
                Revision revision = inbox.catalog.revisions.get(revisionId);
                DriveClient.require(revision != null, "Unknown metadata revision");
                if (!inbox.library.isApplied(revision.notebook, revisionId)) inbox.requireOffer(revisionId, base);
                byte[] bytes = revision.payload == null ? null : inbox.catalog.payloads.get(revision.payload);
                DriveClient.require(bytes == null || revision.notebook.startsWith("folder-"),
                    "Notebook content requires native archive readback");
                if (bytes != null) DriveClient.require(extras.getString("record").equals(
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8)), "Folder readback differs");
                else DriveClient.require(extras.getBoolean("removed"), "Native recycle-bin state was not verified");
                inbox.library.adopt(account, folder, base, revision, bytes, bytes, extras.getString("title", ""));
                return result;
            }
            if ("incoming".equals(method)) {
                DriveClient.require(prefs.getBoolean("automaticIncoming", false), "Incoming sync is paused");
                ((DriveSession) getContext().getApplicationContext()).requestAutomaticSync();
                result.putString("offers", new org.json.JSONArray(
                    new NativeInbox(getContext().getFilesDir(), account, folder).offers()).toString());
                return result;
            }
            if ("begin".equals(method)) {
                Iterator<Stage> iterator = stages.values().iterator();
                while (iterator.hasNext()) {
                    Stage stage = iterator.next();
                    if (android.os.SystemClock.elapsedRealtime() - stage.created > 300000) {
                        stage.file.delete();
                        iterator.remove();
                    }
                }
                DriveClient.require(stages.size() < 4, "Native bridge is busy");
                String id = UUID.randomUUID().toString();
                Stage stage = new Stage();
                stage.file = new File(getContext().getCacheDir(), "native-" + id + ".note");
                stage.account = account;
                stage.folder = folder;
                stage.generation = generation;
                stages.put(id, stage);
                result.putString("id", id);
                return result;
            }
            if ("commit".equals(method)) {
                Stage stage = stages.remove(argument);
                DriveClient.require(stage != null, "Unknown native transfer");
                try {
                    DriveClient.require(stage.opened && stage.account.equals(account) &&
                        stage.folder.equals(folder) && stage.generation.equals(generation),
                        "Drive destination changed during native capture");
                    DriveClient.require(stage.file.length() > 0 && stage.file.length() <= DriveClient.MAX_BYTES,
                        "Notebook exceeds the current 4 MiB transfer limit");
                    byte[] bytes = Files.readAllBytes(stage.file.toPath());
                    DriveClient.require(DriveClient.digest("SHA-256", bytes).equals(extras.getString("sha256")),
                        "Native capture checksum differs");
                    LibraryQueue library = new LibraryQueue(new File(getContext().getFilesDir(), "library"));
                    boolean changed;
                    if (extras.containsKey("incomingRevision")) {
                        DriveClient.require(prefs.getBoolean("automaticIncoming", false), "Incoming sync was paused");
                        String revisionId = extras.getString("incomingRevision"), base = extras.getString("base", "");
                        NativeInbox inbox = new NativeInbox(getContext().getFilesDir(), account, folder);
                        if (!library.isApplied("boox-" + extras.getString("nativeId"), revisionId))
                            inbox.requireOffer(revisionId, base);
                        Revision revision = inbox.catalog.revisions.get(revisionId);
                        DriveClient.require(revision != null &&
                            revision.notebook.equals("boox-" + extras.getString("nativeId")), "Wrong incoming notebook");
                        byte[] incoming = inbox.catalog.payloads.get(revision.payload);
                        new NativeArchive(incoming, extras.getString("nativeId")).verifyReadback(
                            new NativeArchive(bytes, extras.getString("nativeId")));
                        library.adopt(account, folder, base, revision, incoming, bytes, extras.getString("title", ""));
                        changed = false;
                        prefs.edit().putString("nativeStatus", "Incoming notebook applied and verified.").apply();
                    } else {
                        changed = library.capture(account, folder, extras.getString("nativeId"),
                            extras.getString("title", ""), bytes);
                    }
                    prefs.edit().putLong("lastCapture", System.currentTimeMillis())
                        .putString("nativeStatus", changed ? "Saved notebook captured." : "Saved notebook is unchanged.").apply();
                    if (changed) {
                        AutoSyncJob.schedule(getContext(), false);
                        ((DriveSession) getContext().getApplicationContext()).requestAutomaticSync();
                    }
                    result.putBoolean("changed", changed);
                    return result;
                } finally { stage.file.delete(); }
            }
            throw new IllegalArgumentException("Unknown native bridge operation");
        } catch (Exception error) {
            result.putString("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            return result;
        }
    }

    private boolean settingsSystemCaller() {
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) return false;
        try {
            PackageInfo launcher = getContext().getPackageManager().getPackageInfo("com.onyx", 0);
            PackageInfo notes = getContext().getPackageManager().getPackageInfo("com.onyx.android.note", 0);
            return launcher.applicationInfo.uid == android.os.Process.SYSTEM_UID &&
                launcher.getLongVersionCode() == 56737 && notes.getLongVersionCode() == 45326;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        authorize();
        if ("r".equals(mode) && uri.getPathSegments().size() == 2 &&
                "incoming".equals(uri.getPathSegments().get(0))) {
            try {
                SharedPreferences prefs = getContext().getSharedPreferences("drive", 0);
                DriveClient.require(prefs.getBoolean("automatic", false) &&
                    prefs.getBoolean("automaticIncoming", false), "Incoming sync is paused");
                String revision = uri.getLastPathSegment();
                DriveClient.require(Revision.hash(revision), "Invalid incoming revision");
                NativeInbox inbox = new NativeInbox(getContext().getFilesDir(),
                    prefs.getString("account", ""), prefs.getString("folder", ""));
                Revision record = inbox.catalog.revisions.get(revision);
                DriveClient.require(record != null && record.payload != null, "Unknown incoming revision");
                return ParcelFileDescriptor.open(new File(inbox.directory, "payload-" + record.payload + ".note"),
                    ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (Exception error) { throw new FileNotFoundException(error.getMessage()); }
        }
        Stage stage = stages.get(uri.getLastPathSegment());
        if (!"w".equals(mode) || stage == null || stage.opened) throw new FileNotFoundException("Unknown native transfer");
        stage.opened = true;
        return ParcelFileDescriptor.open(stage.file, ParcelFileDescriptor.MODE_CREATE |
            ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        authorize(); throw new UnsupportedOperationException();
    }
    @Override public String getType(Uri uri) { authorize(); return "application/octet-stream"; }
    @Override public Uri insert(Uri uri, ContentValues values) { authorize(); throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { authorize(); throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        authorize(); throw new UnsupportedOperationException();
    }
}
