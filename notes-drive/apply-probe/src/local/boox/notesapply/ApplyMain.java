package local.boox.notesapply;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Root-only, fixture-only closed-notebook apply experiment.
 * No network, scheduler, native-app gate or production sync entry point.
 * Run through app_process while Notes, KSync and the launcher are stopped.
 */
public final class ApplyMain {
    private static final File WORK = new File("/data/local/tmp/boox-notes-apply");
    private static final File DATABASES = new File("/data/user/0/com.onyx.android.note/databases");
    private static final File GLOBAL = new File(DATABASES, "ShapeDatabase.db");
    private static final String[] PARTS = {"database", "document", "point"};
    private static final Set<String> CONTENT_COLUMNS = new HashSet<>(Arrays.asList(
        "updatedAt", "notePageInfo", "noteBackground", "pageCount", "pageNameList",
        "richTextPageNameList", "removePageList", "pageOriginWidth", "pageOriginHeight",
        "miniRequiredVersion", "version"));
    private static String stopAt = "";

    public static void main(String[] args) {
        try (RandomAccessFile file = new RandomAccessFile(new File(WORK, "operation.lock"), "rw");
             FileChannel channel = file.getChannel();
             FileLock lock = channel.tryLock()) {
            require(lock != null, "Another fixture operation is active");
            execute(args);
        } catch (Exception error) {
            System.err.println(error.getClass().getSimpleName() + ": " + error.getMessage());
            System.exit(1);
        }
    }

    private static void execute(String[] args) throws Exception {
        require(Os.getuid() == 0, "Root required for this disposable experiment");
        require(args.length >= 2, "snapshot|prepare|apply|recover|ack|status JOB [ID or checkpoint]");
        File job = new File(WORK, args[1]).getCanonicalFile();
        require(job.getParentFile().equals(WORK) && args[1].matches("[a-zA-Z0-9_-]{1,64}"), "Invalid job");
        String action = args[0];
        if ("snapshot".equals(action)) {
            require(args.length == 3 && !job.exists(), "Fresh snapshot directory required");
            quiescent();
            String id = uuid(args[2]);
            JSONObject row = readRow(id);
            fixture(row);
            require(job.mkdirs(), "Snapshot directory");
            Os.chmod(job.getPath(), 0700);
            for (String part : PARTS) copyTree(target(id, part), new File(job, part), target(id, part));
            writeJson(new File(job, "note.json"), row);
            System.out.println("SNAPSHOT_OK");
            return;
        }
        require(job.isDirectory(), "Missing job");
        if ("prepare".equals(action)) {
            prepare(job);
        } else if ("status".equals(action)) {
            JSONObject journal = readJson(new File(job, "journal.json"));
            System.out.println(journal.getString("state"));
            return;
        } else {
            JSONObject journal = readJson(new File(job, "journal.json"));
            String id = uuid(journal.getString("document"));
            fixture(journal.getJSONObject("beforeRow"));
            if (args.length == 3) stopAt = args[2];
            quiescent();
            if ("apply".equals(action)) apply(job, id, journal);
            else if ("recover".equals(action)) recover(job, id, journal);
            else if ("ack".equals(action)) acknowledge(job, journal);
            else throw new IllegalArgumentException("Unknown action");
        }
        System.out.println(readJson(new File(job, "journal.json")).getString("state"));
    }

    private static void prepare(File job) throws Exception {
        quiescent();
        require(!new File(job, "journal.json").exists(), "Job already prepared");
        Os.chown(job.getPath(), 0, 0);
        Os.chmod(job.getPath(), 0700);
        JSONObject plan = readJson(new File(job, "plan.json"));
        String id = uuid(plan.getString("document"));
        JSONObject before = readRow(id);
        fixture(before);
        require(before.getString("title").startsWith("GDrive-Sync-Probe-Target"), "Target fixture only");
        require(equal(before, plan.getJSONObject("expectedRow")), "Local metadata changed");
        JSONObject after = plan.getJSONObject("afterRow");
        require(keys(before).equals(keys(after)), "Metadata schema differs");
        for (String key : keys(before)) {
            require(CONTENT_COLUMNS.contains(key) || equalValue(before.get(key), after.get(key)),
                    "Attempt to change local identity/settings: " + key);
        }
        clearInactiveSidecars(id);
        JSONObject beforeHashes = new JSONObject();
        JSONObject afterHashes = new JSONObject();
        for (String part : PARTS) {
            File original = target(id, part);
            String originalHash = treeHash(original);
            require(originalHash.equals(plan.getJSONObject("expectedHashes").getString(part)),
                    "Local notebook data changed");
            File candidate = new File(job, "new/" + part);
            String candidateHash = treeHash(candidate);
            require(candidateHash.equals(plan.getJSONObject("newHashes").getString(part)),
                    "Candidate checksum mismatch");
            copyTree(original, new File(job, "old/" + part), original);
            require(originalHash.equals(treeHash(new File(job, "old/" + part))), "Backup readback failed");
            beforeHashes.put(part, originalHash);
            afterHashes.put(part, candidateHash);
        }
        JSONObject journal = new JSONObject()
            .put("schema", 1).put("state", "PREPARED").put("document", id)
            .put("beforeRow", before).put("afterRow", after)
            .put("beforeHashes", beforeHashes).put("afterHashes", afterHashes)
            .put("baseRevision", plan.getString("baseRevision"))
            .put("incomingRevision", plan.getString("incomingRevision"))
            .put("payloadSha256", plan.getString("payloadSha256"))
            .put("appliedRevision", plan.getString("baseRevision"));
        writeJson(new File(job, "journal.json"), journal);
    }

    private static void apply(File job, String id, JSONObject journal) throws Exception {
        require("PREPARED".equals(journal.getString("state")), "Recover a pending job before applying");
        require(equal(readRow(id), journal.getJSONObject("beforeRow")), "Local metadata changed");
        verifyParts(id, journal.getJSONObject("beforeHashes"));
        verifySaved(job, "old", journal.getJSONObject("beforeHashes"));
        verifySaved(job, "new", journal.getJSONObject("afterHashes"));
        clearInactiveSidecars(id);
        state(job, journal, "APPLYING");
        checkpoint("journal-applying");
        for (String part : PARTS) {
            replace(job, id, part, "new");
        }
        quiescent();
        updateRow(id, journal.getJSONObject("beforeRow"), journal.getJSONObject("afterRow"));
        checkpoint("metadata-written");
        verifyParts(id, journal.getJSONObject("afterHashes"));
        require(equal(readRow(id), journal.getJSONObject("afterRow")), "Metadata readback failed");
        state(job, journal, "AWAITING_VERIFICATION");
        checkpoint("awaiting-verification");
    }

    private static void recover(File job, String id, JSONObject journal) throws Exception {
        String currentState = journal.getString("state");
        if ("COMMITTED".equals(currentState) || "ROLLED_BACK".equals(currentState)) return;
        require(Arrays.asList("PREPARED", "APPLYING", "AWAITING_VERIFICATION", "ROLLING_BACK")
                .contains(currentState), "Unknown journal state");
        verifySaved(job, "old", journal.getJSONObject("beforeHashes"));
        JSONObject row = readRow(id);
        require(equal(row, journal.getJSONObject("beforeRow")) ||
                equal(row, journal.getJSONObject("afterRow")), "Recovery paused: notebook metadata changed");
        // Unknown content may be a later user edit. Never erase it during recovery.
        for (String part : PARTS) {
            File file = target(id, part);
            if (!file.exists()) continue;
            String hash = treeHash(file);
            require(hash.equals(journal.getJSONObject("beforeHashes").getString(part)) ||
                    hash.equals(journal.getJSONObject("afterHashes").getString(part)),
                    "Recovery paused: notebook content changed");
        }
        clearInactiveSidecars(id);
        state(job, journal, "ROLLING_BACK");
        checkpoint("journal-rolling-back");
        for (String part : PARTS) replace(job, id, part, "old");
        quiescent();
        JSONObject current = readRow(id);
        require(equal(current, journal.getJSONObject("beforeRow")) ||
                equal(current, journal.getJSONObject("afterRow")), "Metadata changed during recovery");
        updateRow(id, current, journal.getJSONObject("beforeRow"));
        checkpoint("rollback-metadata-written");
        verifyParts(id, journal.getJSONObject("beforeHashes"));
        require(equal(readRow(id), journal.getJSONObject("beforeRow")), "Recovery metadata readback failed");
        state(job, journal, "ROLLED_BACK");
    }

    private static void acknowledge(File job, JSONObject journal) throws Exception {
        require("AWAITING_VERIFICATION".equals(journal.getString("state")), "No verified candidate pending");
        JSONObject proof = readJson(new File(job, "verification.json"));
        require(proof.getString("job").equals(job.getName()) &&
                proof.getString("payloadSha256").equals(journal.getString("payloadSha256")) &&
                proof.getBoolean("nativeReadbackPassed") && proof.getBoolean("identitiesPreserved") &&
                proof.getBoolean("inboundLinksPreserved"), "Incomplete native readback proof");
        require(fileHash(new File(job, "readback.note")).equals(proof.getString("readbackSha256")),
                "Readback artifact changed");
        verifyParts(journal.getString("document"), proof.getJSONObject("currentHashes"));
        require(equal(readRow(journal.getString("document")), proof.getJSONObject("currentRow")),
                "Notebook changed after native readback");
        // The trusted host verifier checks native semantics. Opening/exporting can
        // legitimately change cache files; retain both backups even after commit.
        journal.put("verificationSha256", fileHash(new File(job, "verification.json")));
        journal.put("appliedRevision", journal.getString("incomingRevision"));
        state(job, journal, "COMMITTED");
    }

    private static void replace(File job, String id, String part, String selected) throws Exception {
        File actual = target(id, part);
        File ready = new File(actual.getParentFile(), ".boox-apply-" + job.getName() + "-" + part + "-ready");
        File displaced = new File(actual.getParentFile(), ".boox-apply-" + job.getName() + "-" + part + "-displaced");
        deleteTree(ready);
        copyTree(new File(job, selected + "/" + part), ready, new File(job, "old/" + part));
        quiescent();
        deleteTree(displaced);
        if (actual.exists()) Os.rename(actual.getPath(), displaced.getPath());
        syncDirectory(actual.getParentFile());
        checkpoint(selected + "-" + part + "-old-moved");
        quiescent();
        Os.rename(ready.getPath(), actual.getPath());
        syncDirectory(actual.getParentFile());
        checkpoint(selected + "-" + part + "-new-moved");
        // Immutable job/old remains authoritative if a process dies here.
        deleteTree(displaced);
        syncDirectory(actual.getParentFile());
    }

    private static File target(String id, String part) {
        if ("database".equals(part)) return new File(DATABASES, id + ".db");
        if ("document".equals(part)) return new File("/data/media/0/.ksync/document", id);
        if ("point".equals(part)) return new File("/data/media/0/.ksync/point", id);
        throw new IllegalArgumentException("Unknown component");
    }

    private static void clearInactiveSidecars(String id) throws Exception {
        File database = target(id, "database");
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            File sidecar = new File(database.getPath() + suffix);
            if (!sidecar.exists()) continue;
            if ("-journal".equals(suffix) && sidecar.length() >= 8) {
                try (FileInputStream input = new FileInputStream(sidecar)) {
                    byte[] header = new byte[8];
                    require(input.read(header) == 8 && Arrays.equals(header, new byte[8]),
                            "Hot native journal; recover through SQLite before replacement");
                }
            } else require(sidecar.length() == 0, "Active native database sidecar");
            require(sidecar.delete(), "Cannot remove inactive sidecar");
        }
        syncDirectory(database.getParentFile());
    }

    private static void fixture(JSONObject row) throws Exception {
        require(row.getString("title").startsWith("GDrive-Sync-Probe-"), "Disposable notebook required");
    }

    private static String uuid(String value) {
        require(UUID.fromString(value).toString().equals(value), "Canonical UUID required");
        return value;
    }

    private static JSONObject readRow(String id) throws Exception {
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(GLOBAL.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            return row(database, id);
        }
    }

    private static JSONObject row(SQLiteDatabase database, String id) throws Exception {
        try (Cursor cursor = database.query("NoteModel", null, "uniqueId=?", new String[]{id},
                null, null, null)) {
            require(cursor.moveToFirst() && cursor.getCount() == 1, "Notebook not found");
            JSONObject result = new JSONObject();
            for (int index = 0; index < cursor.getColumnCount(); index++) {
                Object value;
                switch (cursor.getType(index)) {
                    case Cursor.FIELD_TYPE_NULL: value = JSONObject.NULL; break;
                    case Cursor.FIELD_TYPE_INTEGER: value = cursor.getLong(index); break;
                    case Cursor.FIELD_TYPE_FLOAT: value = cursor.getDouble(index); break;
                    case Cursor.FIELD_TYPE_STRING: value = cursor.getString(index); break;
                    default: throw new IllegalStateException("Unexpected metadata blob");
                }
                result.put(cursor.getColumnName(index), value);
            }
            return result;
        }
    }

    private static void updateRow(String id, JSONObject expected, JSONObject replacement) throws Exception {
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(GLOBAL.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            database.beginTransaction();
            try {
                require(equal(row(database, id), expected), "Concurrent metadata change");
                ContentValues values = new ContentValues();
                for (String key : keys(replacement)) {
                    if ("id".equals(key) || "uniqueId".equals(key)) continue;
                    Object value = replacement.get(key);
                    if (value == JSONObject.NULL) values.putNull(key);
                    else if (value instanceof Double || value instanceof Float) values.put(key, ((Number) value).doubleValue());
                    else if (value instanceof Number) values.put(key, ((Number) value).longValue());
                    else values.put(key, (String) value);
                }
                require(database.update("NoteModel", values, "uniqueId=?", new String[]{id}) == 1, "Metadata update failed");
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        }
        syncDirectory(GLOBAL.getParentFile());
    }

    private static void verifyParts(String id, JSONObject hashes) throws Exception {
        for (String part : PARTS)
            require(treeHash(target(id, part)).equals(hashes.getString(part)), "Notebook component changed: " + part);
    }

    private static void verifySaved(File job, String prefix, JSONObject hashes) throws Exception {
        for (String part : PARTS)
            require(treeHash(new File(job, prefix + "/" + part)).equals(hashes.getString(part)),
                    "Saved component checksum mismatch");
    }

    private static void state(File job, JSONObject journal, String value) throws Exception {
        journal.put("state", value);
        writeJson(new File(job, "journal.json"), journal);
    }

    private static void checkpoint(String name) {
        if (name.equals(stopAt)) {
            System.out.println("STOP_AT " + name);
            System.out.flush();
            android.os.Process.killProcess(android.os.Process.myPid());
            Runtime.getRuntime().halt(137);
        }
    }

    private static void quiescent() throws Exception {
        for (String name : new String[]{"com.onyx.android.note", "com.onyx.android.ksync", "com.onyx"}) {
            Process process = new ProcessBuilder("/system/bin/pidof", name).start();
            require(process.waitFor() != 0, "Close the Notes/launcher/KSync processes before this fixture operation");
        }
    }

    private static JSONObject readJson(File file) throws Exception {
        byte[] bytes = new AtomicFile(file).readFully();
        require(bytes.length < 1024 * 1024, "Oversized job record");
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void writeJson(File file, JSONObject value) throws Exception {
        File parent = file.getParentFile();
        require(parent.isDirectory() || parent.mkdirs(), "Journal directory");
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(output);
            Os.chmod(file.getPath(), 0600);
            syncDirectory(parent);
        } catch (Throwable error) {
            atomic.failWrite(output);
            throw error;
        }
    }

    private static Set<String> keys(JSONObject object) {
        Set<String> result = new HashSet<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }

    private static boolean equal(JSONObject first, JSONObject second) throws Exception {
        if (!keys(first).equals(keys(second))) return false;
        for (String key : keys(first)) if (!equalValue(first.get(key), second.get(key))) return false;
        return true;
    }

    private static boolean equalValue(Object first, Object second) {
        if (first instanceof Number && second instanceof Number)
            return new BigDecimal(first.toString()).compareTo(new BigDecimal(second.toString())) == 0;
        return first == JSONObject.NULL ? second == JSONObject.NULL : first.toString().equals(second.toString());
    }

    private static void copyTree(File source, File destination, File attributes) throws Exception {
        require(!Files.isSymbolicLink(source.toPath()) && source.exists(), "Missing/linked component");
        File parent = destination.getParentFile();
        require(parent.isDirectory() || parent.mkdirs(), "Copy parent");
        if (source.isDirectory()) {
            require(destination.mkdir(), "Destination already exists");
            File[] children = source.listFiles();
            require(children != null && children.length < 2048, "Invalid fixture directory");
            for (File child : children) {
                File reference = new File(attributes, child.getName());
                if (!reference.exists()) reference = child.isDirectory() ? firstDirectory(attributes) : firstFile(attributes);
                copyTree(child, new File(destination, child.getName()), reference);
            }
            copyAttributes(attributes, destination);
            syncDirectory(destination);
        } else {
            require(source.length() < 64 * 1024 * 1024, "Oversized fixture component");
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[65536];
                int size;
                while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                output.getFD().sync();
            }
            copyAttributes(attributes, destination);
            try (FileInputStream input = new FileInputStream(destination)) { input.getFD().sync(); }
        }
    }

    private static File firstDirectory(File root) {
        require(root.isDirectory(), "Directory attributes unavailable");
        return root;
    }

    private static File firstFile(File root) {
        if (root.isFile()) return root;
        File[] children = root.listFiles();
        require(children != null, "File attributes unavailable");
        for (File child : children) if (child.isFile()) return child;
        for (File child : children) if (child.isDirectory()) {
            try { return firstFile(child); } catch (IllegalStateException ignored) { }
        }
        throw new IllegalStateException("File attributes unavailable");
    }

    private static void copyAttributes(File source, File destination) throws Exception {
        StructStat attributes = Os.stat(source.getPath());
        Os.chown(destination.getPath(), attributes.st_uid, attributes.st_gid);
        Os.chmod(destination.getPath(), attributes.st_mode & 07777);
        Class<?> selinux = Class.forName("android.os.SELinux");
        String context = (String) selinux.getMethod("getFileContext", String.class).invoke(null, source.getPath());
        require(context != null && Boolean.TRUE.equals(selinux.getMethod("setFileContext", String.class, String.class)
                .invoke(null, destination.getPath(), context)), "SELinux context copy failed");
    }

    private static void deleteTree(File file) throws Exception {
        if (!file.exists()) return;
        require(!Files.isSymbolicLink(file.toPath()), "Refuse linked cleanup target");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            require(children != null, "Cannot list cleanup target");
            for (File child : children) deleteTree(child);
        }
        require(file.delete(), "Cannot remove temporary component");
    }

    private static String treeHash(File root) throws Exception {
        require(root.exists(), "Missing component");
        TreeMap<String, String> entries = new TreeMap<>();
        collect(root, "", entries);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, String> entry : entries.entrySet())
            digest.update((entry.getKey() + "=" + entry.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
        return hex(digest.digest());
    }

    private static void collect(File file, String relative, TreeMap<String, String> entries) throws Exception {
        require(!Files.isSymbolicLink(file.toPath()), "Linked component");
        if (file.isDirectory()) {
            entries.put(relative + "/", "directory");
            File[] children = file.listFiles();
            require(children != null, "Unreadable component");
            for (File child : children) collect(child, relative.isEmpty() ? child.getName() :
                    relative + "/" + child.getName(), entries);
        } else entries.put(relative, fileHash(file));
        require(entries.size() < 4096, "Oversized fixture tree");
    }

    private static String fileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int size;
            while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format("%02x", value & 255));
        return text.toString();
    }

    private static void syncDirectory(File directory) throws Exception {
        require(directory.isDirectory(), "Directory required");
        FileDescriptor descriptor = Os.open(directory.getPath(), OsConstants.O_RDONLY, 0);
        try { Os.fsync(descriptor); } finally { Os.close(descriptor); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
