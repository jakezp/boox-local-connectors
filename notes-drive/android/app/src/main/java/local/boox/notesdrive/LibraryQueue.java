package local.boox.notesdrive;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/** Latest native capture plus a durable local branch, independent of network availability. */
final class LibraryQueue {
    static final Object LOCK = new Object();
    private final File root, captures, states, payloads;

    LibraryQueue(File root) throws Exception {
        this.root = root;
        captures = new File(root, "captures");
        states = new File(root, "states");
        payloads = new File(root, "payloads");
        for (File dir : Arrays.asList(root, captures, states, payloads))
            DriveClient.require(dir.isDirectory() || dir.mkdirs(), "Cannot create library journal");
        RevisionQueue.syncDirectory(root);
        RevisionQueue.syncDirectory(root.getParentFile());
        synchronized (LOCK) { recoverAdoption(); }
    }

    boolean capture(String account, String folder, String nativeId, String title, byte[] bytes) throws Exception {
        return captureRecord(account, folder, "boox-" + nativeId, title, bytes);
    }

    boolean captureRecord(String account, String folder, String notebook, String title, byte[] bytes) throws Exception {
        synchronized (LOCK) {
            recoverAdoption();
            String fingerprint = fingerprint(notebook, bytes);
            File destination = new File(captures, notebook + ".json");
            if (destination.exists()) {
                JSONObject previous = read(destination);
                binding(previous, account, folder);
                if (title.isEmpty()) title = previous.optString("title", "");
                if (fingerprint.equals(previous.getString("fingerprint"))) {
                    if (!title.isEmpty() && previous.optString("title", "").isEmpty())
                        write(destination, previous.put("title", title));
                    return false;
                }
            }
            File[] existing = captures.listFiles();
            DriveClient.require(existing != null && (destination.exists() || existing.length < 200),
                "Library exceeds the current 200 notebook limit");
            String hash = bytes == null ? null : DriveClient.digest("SHA-256", bytes);
            if (bytes != null) RevisionQueue.write(new File(payloads, hash + ".note"), bytes);
            JSONObject capture = new JSONObject().put("schema", 1).put("account", account)
                .put("folder", folder).put("notebook", notebook).put("title", title)
                .put("payload", hash == null ? JSONObject.NULL : hash).put("fingerprint", fingerprint);
            write(destination, capture);
            prunePayloads();
            return true;
        }
    }

    int stage(String account, String folder, String device, RevisionQueue outgoing) throws Exception {
        synchronized (LOCK) {
            recoverAdoption();
            File[] files = captures.listFiles((dir, name) -> name.endsWith(".json"));
            DriveClient.require(files != null && files.length <= 200, "Invalid native capture journal");
            Arrays.sort(files);
            int count = 0;
            for (File file : files) {
                JSONObject capture = read(file);
                binding(capture, account, folder);
                String notebook = capture.getString("notebook");
                DriveClient.require(file.getName().equals(notebook + ".json"), "Invalid capture identity");
                File stateFile = new File(states, file.getName());
                JSONObject state = stateFile.exists() ? read(stateFile) : null;
                Revision previous = null;
                if (state != null) {
                    binding(state, account, folder);
                    previous = Revision.decode(state.getString("revision").getBytes(StandardCharsets.UTF_8));
                    // Recover a crash between branch assignment and outgoing journal insertion.
                    if (!state.getBoolean("enqueued")) {
                        enqueue(outgoing, account, folder, previous);
                        state.put("enqueued", true);
                        write(stateFile, state);
                    }
                    if (state.getString("fingerprint").equals(capture.getString("fingerprint"))) continue;
                }
                Revision revision = new Revision(notebook, device,
                    capture.isNull("payload") ? null : capture.getString("payload"),
                    previous == null ? Collections.emptyList() : Collections.singletonList(previous.id()));
                JSONObject next = new JSONObject().put("schema", 1).put("account", account).put("folder", folder)
                    .put("fingerprint", capture.getString("fingerprint")).put("enqueued", false)
                    .put("revision", new String(revision.encode(), StandardCharsets.UTF_8));
                write(stateFile, next);
                enqueue(outgoing, account, folder, revision);
                next.put("enqueued", true);
                write(stateFile, next);
                count++;
            }
            return count;
        }
    }

    /** Returns a clean local branch only when every latest capture belongs to it. */
    JSONObject branch(String account, String folder, String notebook) throws Exception {
        synchronized (LOCK) {
            recoverAdoption();
            DriveClient.require(Revision.identifier(notebook), "Invalid notebook");
            File stateFile = new File(states, notebook + ".json");
            File captureFile = new File(captures, notebook + ".json");
            if (!stateFile.exists()) {
                DriveClient.require(!captureFile.exists(), "Local notebook has unqueued changes");
                return null;
            }
            JSONObject state = read(stateFile);
            binding(state, account, folder);
            JSONObject capture = read(captureFile);
            binding(capture, account, folder);
            DriveClient.require(state.getBoolean("enqueued") &&
                state.getString("fingerprint").equals(capture.getString("fingerprint")),
                "Local notebook has pending changes");
            return state;
        }
    }

    void adopt(String account, String folder, String base, Revision revision,
            byte[] incoming, byte[] readback, String title) throws Exception {
        synchronized (LOCK) {
            recoverAdoption();
            JSONObject state = branch(account, folder, revision.notebook);
            String current = state == null ? "" :
                Revision.decode(state.getString("revision").getBytes(StandardCharsets.UTF_8)).id();
            if (current.equals(revision.id())) return; // An acknowledgment may have lost its response.
            DriveClient.require(current.equals(base), "Local branch changed during native application");
            DriveClient.require(revision.payload == null ? incoming == null && readback == null :
                incoming != null && revision.payload.equals(DriveClient.digest("SHA-256", incoming)),
                "Incoming payload differs");
            String fingerprint = fingerprint(revision.notebook, readback);
            String hash = readback == null ? null : DriveClient.digest("SHA-256", readback);
            if (incoming != null) RevisionQueue.write(new File(payloads, revision.payload + ".note"), incoming);
            if (readback != null) RevisionQueue.write(new File(payloads, hash + ".note"), readback);
            JSONObject capture = new JSONObject().put("schema", 1).put("account", account)
                .put("folder", folder).put("notebook", revision.notebook).put("title", title)
                .put("payload", hash == null ? JSONObject.NULL : hash).put("fingerprint", fingerprint);
            JSONObject next = new JSONObject().put("schema", 1).put("account", account).put("folder", folder)
                .put("fingerprint", fingerprint).put("enqueued", true)
                .put("revision", new String(revision.encode(), StandardCharsets.UTF_8));
            // Replay intent before any capture or branch read after a process restart.
            write(new File(root, "adopt.json"), new JSONObject().put("notebook", revision.notebook)
                .put("capture", capture).put("state", next));
            recoverAdoption();
            prunePayloads();
        }
    }

    private void recoverAdoption() throws Exception {
        File intent = new File(root, "adopt.json");
        if (!intent.exists()) return;
        JSONObject value = read(intent);
        String notebook = value.getString("notebook");
        DriveClient.require(Revision.identifier(notebook), "Invalid adoption identity");
        write(new File(captures, notebook + ".json"), value.getJSONObject("capture"));
        write(new File(states, notebook + ".json"), value.getJSONObject("state"));
        Files.delete(intent.toPath());
        RevisionQueue.syncDirectory(root);
    }

    boolean isApplied(String notebook, String revision) throws Exception {
        synchronized (LOCK) {
            recoverAdoption();
            DriveClient.require(Revision.identifier(notebook) && Revision.hash(revision), "Invalid acknowledgment query");
            File file = new File(states, notebook + ".json");
            return file.exists() && Revision.decode(read(file).getString("revision")
                .getBytes(StandardCharsets.UTF_8)).id().equals(revision);
        }
    }

    List<String> managed() throws Exception {
        synchronized (LOCK) {
            recoverAdoption();
            File[] files = captures.listFiles((directory, name) -> name.endsWith(".json"));
            DriveClient.require(files != null, "Cannot inspect managed library");
            List<String> result = new java.util.ArrayList<>();
            for (File file : files) result.add(read(file).getString("notebook"));
            return result;
        }
    }

    private static String fingerprint(String notebook, byte[] bytes) throws Exception {
        DriveClient.require(Revision.identifier(notebook) &&
            (notebook.startsWith("boox-") || notebook.startsWith("folder-")), "Invalid library identity");
        if (bytes == null) return "deleted";
        if (notebook.startsWith("folder-")) {
            DriveClient.require(notebook.equals("folder-" + FolderRecord.decode(bytes).id), "Folder identity differs");
            return DriveClient.digest("SHA-256", bytes);
        }
        return NativeSnapshot.fingerprint(bytes, notebook.substring(5));
    }

    private void prunePayloads() throws Exception {
        java.util.Set<String> retained = new java.util.HashSet<>();
        for (File directory : Arrays.asList(captures, states)) {
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
            DriveClient.require(files != null, "Cannot inspect native journal");
            for (File file : files) {
                JSONObject value = read(file);
                String hash = directory.equals(captures) ? (value.isNull("payload") ? null : value.getString("payload")) :
                    Revision.decode(value.getString("revision").getBytes(StandardCharsets.UTF_8)).payload;
                if (hash != null) retained.add(hash + ".note");
            }
        }
        File[] files = payloads.listFiles((dir, name) -> name.endsWith(".note"));
        DriveClient.require(files != null, "Cannot inspect native payloads");
        for (File file : files) if (!retained.contains(file.getName())) Files.delete(file.toPath());
        RevisionQueue.syncDirectory(payloads);
    }

    private void enqueue(RevisionQueue queue, String account, String folder, Revision revision) throws Exception {
        byte[] bytes = revision.payload == null ? null :
            Files.readAllBytes(new File(payloads, revision.payload + ".note").toPath());
        queue.enqueue(account, folder, revision, bytes);
    }

    static void binding(JSONObject object, String account, String folder) throws Exception {
        DriveClient.require(object.getInt("schema") == 1 && !account.isEmpty() &&
            account.equals(object.getString("account")) && folder.equals(object.getString("folder")),
            "Native snapshot belongs to another Drive account or directory");
    }

    private static JSONObject read(File file) throws Exception {
        DriveClient.require(file.length() <= 32768, "Native journal record exceeds limit");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private static void write(File file, JSONObject object) throws Exception {
        RevisionQueue.write(file, object.toString().getBytes(StandardCharsets.UTF_8));
    }
}
