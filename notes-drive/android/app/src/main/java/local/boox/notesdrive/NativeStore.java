package local.boox.notesdrive;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per-notebook recovery journal owned by Notes. Never restores another notebook's rows. */
final class NativeStore {
    static final Object GATE = new Object();
    static final java.util.concurrent.locks.ReentrantLock OPERATION =
        new java.util.concurrent.locks.ReentrantLock(true);
    static final ThreadLocal<Boolean> INTERNAL = ThreadLocal.withInitial(() -> false);
    final Context context;
    final File root;
    NativeStore(Context context) throws Exception {
        this.context = context;
        root = new File(context.getFilesDir(), "boox_drive_apply");
        mkdir(root);
    }
    File active() { return new File(root, "active.json"); }
    File job(String revision) throws Exception {
        DriveClient.require(Revision.hash(revision), "Invalid apply revision");
        return new File(root, revision);
    }
    File document(String id) { return new File("/storage/emulated/0/.ksync/document", id); }
    File point(String id) { return new File("/storage/emulated/0/.ksync/point", id); }
    File database(String id) { return context.getDatabasePath(id + ".db"); }

    JSONObject prepare(String id, String revision, String base, String generation, NativeArchive archive)
            throws Exception {
        DriveClient.require(!active().exists(), "Recover the preceding native update first");
        if (archive != null) {
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath("ShapeDatabase.db").getPath(),
                    null, SQLiteDatabase.OPEN_READONLY)) {
                for (Map.Entry<String, Set<String>> table : archive.sharedIdentities.entrySet())
                    for (String identity : table.getValue())
                        try (Cursor cursor = db.query(quoted(table.getKey()), new String[]{"documentId"},
                                "uniqueId=?", new String[]{identity}, null, null, null)) {
                            while (cursor.moveToNext()) DriveClient.require(id.equals(cursor.getString(0)),
                                "Incoming resource, tag or link ID belongs to another native notebook");
                        }
            }
        }
        File job = job(revision);
        if (job.exists()) {
            // Keep evidence from previous attempts; each current attempt has an immutable predecessor.
            File previous = new File(root, revision + "-previous-" + UUID.randomUUID());
            Files.move(job.toPath(), previous.toPath());
        }
        mkdir(job);
        JSONObject journal = new JSONObject().put("schema", 1).put("id", id).put("revision", revision)
            .put("base", base).put("generation", generation).put("phase", "PREPARED")
            .put("validationFixture", archive != null && archive.title.startsWith("GDrive-Sync-Probe"));
        JSONObject hashes = new JSONObject();
        for (String part : Arrays.asList("database", "document", "point")) {
            File source = target(id, part), destination = new File(job, "old/" + part);
            if (source.exists()) copy(source, destination);
            hashes.put(part, hash(source));
            DriveClient.require(hash(source).equals(hash(destination)), "Native backup readback differs");
        }
        JSONObject rows = snapshotRows(id);
        json(new File(job, "old-rows.json"), rows);
        journal.put("oldHashes", hashes).put("oldRowsHash", hash(new File(job, "old-rows.json")));
        if (archive == null) {
            journal.put("metadataOnly", true);
            json(new File(job, "journal.json"), journal);
            json(active(), journal);
            return journal;
        }
        File newDocument = new File(job, "new/document"), newPoint = new File(job, "new/point");
        mkdir(newDocument); mkdir(newPoint);
        for (Map.Entry<String, byte[]> entry : archive.files.entrySet()) {
            String path = entry.getKey();
            File destination = path.startsWith("point/") ?
                new File(newPoint, path.substring(6)) : new File(newDocument, path);
            File selected = path.startsWith("point/") ? newPoint : newDocument;
            DriveClient.require(destination.getCanonicalPath().startsWith(selected.getCanonicalPath() + "/"),
                "Archive extraction escaped notebook directory");
            mkdir(destination.getParentFile());
            RevisionQueue.write(destination, entry.getValue());
        }
        journal.put("newDocumentHash", hash(newDocument)).put("newPointHash", hash(newPoint));
        json(new File(job, "journal.json"), journal);
        json(active(), journal);
        checkpoint(journal, "PREPARED");
        return journal;
    }
    void phase(JSONObject journal, String phase) throws Exception {
        journal.put("phase", phase);
        json(new File(job(journal.getString("revision")), "journal.json"), journal);
        json(active(), journal);
        checkpoint(journal, phase);
    }
    /** Root-only local validation control; no provider, intent or remote revision can arm it. */
    void checkpoint(JSONObject journal, String phase) throws Exception {
        File fault = new File(root, "validation-fault.json");
        if (!journal.optBoolean("validationFixture") || !fault.isFile()) return;
        JSONObject selected = read(fault);
        if (!journal.getString("id").equals(selected.optString("id")) ||
                !phase.equals(selected.optString("phase")) ||
                (!selected.optString("revision").isEmpty() &&
                    !journal.getString("revision").equals(selected.getString("revision")))) return;
        Files.move(fault.toPath(), new File(job(journal.getString("revision")),
            "consumed-fault-" + UUID.randomUUID() + ".json").toPath());
        json(new File(root, "last-validation-fault.json"), new JSONObject()
            .put("id", journal.getString("id")).put("revision", journal.getString("revision"))
            .put("phase", phase));
        json(new File(root, "validation-paused.json"), new JSONObject()
            .put("revision", journal.getString("revision")));
        android.os.Process.killProcess(android.os.Process.myPid());
        throw new java.io.IOException("Validation process termination returned unexpectedly");
    }
    void replaceFiles(JSONObject journal) throws Exception {
        String id = journal.getString("id");
        File job = job(journal.getString("revision"));
        DriveClient.require(hash(new File(job, "new/document")).equals(journal.getString("newDocumentHash")) &&
            hash(new File(job, "new/point")).equals(journal.getString("newPointHash")), "Incoming files changed");
        phase(journal, "APPLYING");
        replace(new File(job, "new/document"), document(id));
        checkpoint(journal, "DOCUMENT_REPLACED");
        replace(new File(job, "new/point"), point(id));
        checkpoint(journal, "POINTS_REPLACED");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database(id).getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            db.beginTransaction();
            try {
                List<String> tables = tables(db);
                for (String table : tables) if (!table.equals("android_metadata") && !table.startsWith("sqlite_"))
                    db.delete(quoted(table), null, null);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        clearOwnedRows(id, false);
        checkpoint(journal, "ROWS_CLEARED");
    }
    void finish(JSONObject journal) throws Exception {
        if (journal.has("nativeReceipt")) {
            String receipt = journal.getString("nativeReceipt");
            DriveClient.require(Revision.hash(receipt), "Invalid native capture receipt");
            DriveClient.require(context.getSharedPreferences("boox_drive_capture", 0).edit()
                .putString(journal.getString("id"), receipt).commit(), "Cannot persist recovered capture receipt");
        }
        phase(journal, "COMMITTED");
        Files.delete(active().toPath());
        RevisionQueue.syncDirectory(root);
    }
    void rollback(JSONObject journal) throws Exception {
        String id = journal.getString("id");
        File job = job(journal.getString("revision"));
        for (String part : Arrays.asList("database", "document", "point"))
            DriveClient.require(hash(new File(job, "old/" + part)).equals(
                journal.getJSONObject("oldHashes").getString(part)), "Saved native backup changed");
        DriveClient.require(hash(new File(job, "old-rows.json")).equals(journal.getString("oldRowsHash")),
            "Saved native metadata backup changed");
        // Preserve interrupted bytes before rollback, including any unexpected external modification.
        File interrupted = new File(job, "interrupted-" + UUID.randomUUID());
        mkdir(interrupted);
        for (String part : Arrays.asList("database", "document", "point"))
            if (target(id, part).exists()) copy(target(id, part), new File(interrupted, part));
        json(new File(interrupted, "rows.json"), snapshotRows(id));
        phase(journal, "ROLLING_BACK");
        for (String part : Arrays.asList("database", "document", "point")) {
            if (journal.optBoolean("metadataOnly")) continue;
            if (part.equals("database"))
                for (String suffix : Arrays.asList("-wal", "-shm", "-journal"))
                    delete(new File(database(id).getPath() + suffix));
            replace(new File(job, "old/" + part), target(id, part));
        }
        restoreRows(id, read(new File(job, "old-rows.json")));
        phase(journal, "ROLLED_BACK");
        Files.delete(active().toPath());
        RevisionQueue.syncDirectory(root);
    }
    File target(String id, String part) {
        if (part.equals("database")) return database(id);
        return part.equals("document") ? document(id) : point(id);
    }

    private JSONObject snapshotRows(String id) throws Exception {
        JSONObject all = new JSONObject();
        for (String name : Arrays.asList("ShapeDatabase.db", "NoteRecordDatabase.db", "KSyncRecordDatabase.db")) {
            File file = context.getDatabasePath(name);
            if (!file.exists()) continue;
            JSONObject selected = new JSONObject();
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                for (String table : tables(db)) {
                    String key = ownedKey(db, table);
                    if (key == null) continue;
                    JSONArray rows = new JSONArray();
                    try (Cursor cursor = db.query(quoted(table), null, quoted(key) + "=?", new String[]{id},
                            null, null, null)) {
                        while (cursor.moveToNext()) rows.put(row(cursor));
                    }
                    selected.put(table, new JSONObject().put("key", key).put("rows", rows));
                }
            }
            all.put(name, selected);
        }
        return all;
    }
    private void clearOwnedRows(String id, boolean note) throws Exception {
        JSONObject template = snapshotRows(id);
        for (String name : keys(template)) {
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath(name).getPath(),
                    null, SQLiteDatabase.OPEN_READWRITE)) {
                db.beginTransaction();
                try {
                    for (String table : keys(template.getJSONObject(name))) {
                        if (!note && table.equals("NoteModel")) continue;
                        String key = template.getJSONObject(name).getJSONObject(table).getString("key");
                        db.delete(quoted(table), quoted(key) + "=?", new String[]{id});
                    }
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
        }
    }
    private void restoreRows(String id, JSONObject all) throws Exception {
        for (String name : keys(all)) {
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath(name).getPath(),
                    null, SQLiteDatabase.OPEN_READWRITE)) {
                db.beginTransaction();
                try {
                    JSONObject tables = all.getJSONObject(name);
                    for (String table : keys(tables)) {
                        JSONObject data = tables.getJSONObject(table);
                        db.delete(quoted(table), quoted(data.getString("key")) + "=?", new String[]{id});
                        JSONArray rows = data.getJSONArray("rows");
                        for (int i = 0; i < rows.length(); i++)
                            DriveClient.require(db.insertOrThrow(quoted(table), null, values(rows.getJSONObject(i))) != -1,
                                "Native row restore failed");
                    }
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
        }
    }
    private static String ownedKey(SQLiteDatabase db, String table) throws Exception {
        if (table.equals("NoteModel")) return "uniqueId";
        Set<String> names = new HashSet<>();
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + quoted(table) + ")", null)) {
            while (cursor.moveToNext()) names.add(cursor.getString(1));
        }
        if (names.contains("documentId")) return "documentId";
        if (names.contains("documentUniqueId")) return "documentUniqueId";
        return null;
    }
    private static List<String> tables(SQLiteDatabase db) {
        List<String> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return result;
    }
    private static String quoted(String value) throws Exception {
        DriveClient.require(value.matches("[A-Za-z_][A-Za-z_0-9]*"), "Unexpected native database identifier");
        return "\"" + value + "\"";
    }
    static JSONObject row(Cursor cursor) throws Exception {
        JSONObject row = new JSONObject();
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            Object value;
            switch (cursor.getType(i)) {
                case Cursor.FIELD_TYPE_NULL: value = JSONObject.NULL; break;
                case Cursor.FIELD_TYPE_INTEGER: value = cursor.getLong(i); break;
                case Cursor.FIELD_TYPE_FLOAT: value = cursor.getDouble(i); break;
                case Cursor.FIELD_TYPE_BLOB:
                    value = new JSONObject().put("blob", android.util.Base64.encodeToString(
                        cursor.getBlob(i), android.util.Base64.NO_WRAP)); break;
                default: value = cursor.getString(i);
            }
            row.put(cursor.getColumnName(i), value);
        }
        return row;
    }
    private static ContentValues values(JSONObject row) throws Exception {
        ContentValues values = new ContentValues();
        for (String name : keys(row)) {
            Object value = row.get(name);
            if (value == JSONObject.NULL) values.putNull(name);
            else if (value instanceof JSONObject) values.put(name, android.util.Base64.decode(
                ((JSONObject) value).getString("blob"), android.util.Base64.NO_WRAP));
            else if (value instanceof Double || value instanceof Float) values.put(name, ((Number) value).doubleValue());
            else if (value instanceof Number) values.put(name, ((Number) value).longValue());
            else values.put(name, (String) value);
        }
        return values;
    }
    static Set<String> keys(JSONObject value) {
        Set<String> result = new TreeSet<>();
        Iterator<String> names = value.keys();
        while (names.hasNext()) result.add(names.next());
        return result;
    }
    static JSONObject read(File file) throws Exception {
        DriveClient.require(file.length() <= 16 * 1024 * 1024, "Native journal exceeds limit");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    static void json(File file, JSONObject data) throws Exception {
        mkdir(file.getParentFile());
        RevisionQueue.write(file, data.toString().getBytes(StandardCharsets.UTF_8));
    }
    static void mkdir(File file) throws Exception {
        DriveClient.require(file.isDirectory() || file.mkdirs(), "Cannot create native staging directory");
    }
    static String hash(File file) throws Exception {
        if (!file.exists()) return "absent";
        DriveClient.require(!Files.isSymbolicLink(file.toPath()), "Linked native component");
        if (file.isFile()) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest())
                hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        }
        StringBuilder result = new StringBuilder();
        File[] children = file.listFiles();
        DriveClient.require(children != null, "Cannot read native component");
        Arrays.sort(children);
        for (File child : children) result.append(child.getName()).append('\0').append(hash(child)).append('\n');
        return DriveClient.digest("SHA-256", result.toString().getBytes(StandardCharsets.UTF_8));
    }
    static void copy(File source, File destination) throws Exception {
        DriveClient.require(!Files.isSymbolicLink(source.toPath()), "Linked native component");
        if (source.isDirectory()) {
            mkdir(destination);
            File[] children = source.listFiles();
            DriveClient.require(children != null, "Cannot read native backup");
            for (File child : children) copy(child, new File(destination, child.getName()));
            RevisionQueue.syncDirectory(destination);
        } else {
            mkdir(destination.getParentFile());
            try (FileOutputStream out = new FileOutputStream(destination)) {
                Files.copy(source.toPath(), out);
                out.getFD().sync();
            }
            RevisionQueue.syncDirectory(destination.getParentFile());
        }
    }
    static void delete(File file) throws Exception {
        DriveClient.require(!Files.isSymbolicLink(file.toPath()), "Linked native component");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            DriveClient.require(children != null, "Cannot inspect native directory");
            for (File child : children) delete(child);
        }
        Files.deleteIfExists(file.toPath());
    }
    private static void replace(File source, File target) throws Exception {
        File staged = new File(target.getParentFile(), ".drive-ready-" + UUID.randomUUID());
        mkdir(target.getParentFile());
        if (source.exists()) copy(source, staged);
        delete(target);
        if (staged.exists()) Files.move(staged.toPath(), target.toPath());
        RevisionQueue.syncDirectory(target.getParentFile());
    }
}
