package local.boox.notesdrive;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/** App-private outgoing journal. Network work starts only after durable staging. */
final class RevisionQueue {
    private final File root, jobs, payloads;
    interface Publisher { void publish(Revision revision, byte[] bytes) throws Exception; }

    RevisionQueue(File root) throws Exception {
        this.root = root;
        jobs = new File(root, "jobs");
        payloads = new File(root, "payloads");
        for (File dir : Arrays.asList(root, jobs, payloads))
            DriveClient.require(dir.isDirectory() || dir.mkdirs(), "Cannot create revision journal");
        syncDirectory(root);
        if (root.getParentFile() != null) syncDirectory(root.getParentFile());
    }

    synchronized void enqueue(String account, String folder, Revision revision, byte[] bytes) throws Exception {
        DriveClient.require(account != null && !account.isEmpty() && account.length() <= 256,
            "Missing queued account");
        DriveClient.fileId(folder);
        DriveClient.require(revision.payload == null ? bytes == null : bytes != null &&
            bytes.length > 0 && bytes.length <= DriveClient.MAX_BYTES &&
            DriveClient.digest("SHA-256", bytes).equals(revision.payload), "Queued payload differs");
        String id = revision.id();
        File file = new File(jobs, id + ".json");
        if (file.exists()) {
            JSONObject old = read(file);
            DriveClient.require(account.equals(old.getString("account")) &&
                folder.equals(old.getString("folder")) &&
                Arrays.equals(revision.encode(), old.getString("revision").getBytes(StandardCharsets.UTF_8)),
                "Revision is already queued for another destination");
            return;
        }
        pruneVerified();
        DriveClient.require(files().size() < 256, "Outgoing journal is full");
        if (revision.payload != null) {
            File payload = new File(payloads, revision.payload + ".note");
            if (payload.exists())
                DriveClient.require(revision.payload.equals(DriveClient.digest("SHA-256", readBytes(payload))),
                    "Saved payload is damaged");
            else write(payload, bytes);
        }
        JSONObject job = new JSONObject().put("schema", 1).put("account", account).put("folder", folder)
            .put("revision", new String(revision.encode(), StandardCharsets.UTF_8)).put("state", "pending");
        write(file, job.toString().getBytes(StandardCharsets.UTF_8));
    }

    synchronized int retry(String account, String folder, Publisher publisher) throws Exception {
        int completed = 0;
        java.util.Map<String, File> pending = new java.util.LinkedHashMap<>();
        for (File file : files()) {
            JSONObject job = read(file);
            Revision revision = revision(file, job);
            if ("verified".equals(job.getString("state"))) continue;
            DriveClient.require("pending".equals(job.getString("state")), "Unknown journal state");
            DriveClient.require(account.equals(job.getString("account")) && folder.equals(job.getString("folder")),
                "Pending revision belongs to another Drive account or directory");
            pending.put(revision.id(), file);
        }
        while (!pending.isEmpty()) {
            boolean progress = false;
            java.util.Iterator<java.util.Map.Entry<String, File>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                File file = iterator.next().getValue();
                JSONObject job = read(file);
                Revision revision = revision(file, job);
                boolean waiting = false;
                for (String parent : revision.parents) if (pending.containsKey(parent)) waiting = true;
                if (waiting) continue;
                byte[] payload = revision.payload == null ? null : readBytes(new File(payloads, revision.payload + ".note"));
                DriveClient.require(revision.payload == null || revision.payload.equals(DriveClient.digest("SHA-256", payload)),
                    "Queued notebook checksum differs");
                publisher.publish(revision, payload);
                job.put("state", "verified");
                write(file, job.toString().getBytes(StandardCharsets.UTF_8));
                iterator.remove();
                completed++;
                progress = true;
            }
            DriveClient.require(progress, "Outgoing ancestry contains a cycle");
        }
        pruneVerified();
        return completed;
    }

    private void pruneVerified() throws Exception {
        List<File> verified = new ArrayList<>();
        for (File file : files()) if ("verified".equals(read(file).getString("state"))) verified.add(file);
        // Remote revisions remain immutable. Only completed local receipts are compacted.
        for (int i = 0; i < verified.size() - 16; i++) Files.delete(verified.get(i).toPath());
        syncDirectory(jobs);
        java.util.Set<String> retained = new java.util.HashSet<>();
        for (File file : files()) retained.add(revision(file, read(file)).payload + ".note");
        File[] stored = payloads.listFiles((dir, name) -> name.endsWith(".note"));
        DriveClient.require(stored != null, "Cannot inspect outgoing payload journal");
        for (File file : stored) if (!retained.contains(file.getName())) Files.delete(file.toPath());
        syncDirectory(payloads);
    }
    synchronized int pending() throws Exception {
        int count = 0;
        for (File file : files()) {
            JSONObject job = read(file);
            revision(file, job);
            String state = job.getString("state");
            DriveClient.require("pending".equals(state) || "verified".equals(state), "Unknown journal state");
            if ("pending".equals(state)) count++;
        }
        return count;
    }

    private Revision revision(File file, JSONObject job) throws Exception {
        DriveClient.require(job.getInt("schema") == 1, "Unsupported queue schema");
        Revision revision = Revision.decode(job.getString("revision").getBytes(StandardCharsets.UTF_8));
        DriveClient.require(file.getName().equals(revision.id() + ".json"),
            "Damaged revision journal");
        return revision;
    }

    private List<File> files() throws Exception {
        File[] files = jobs.listFiles((dir, name) -> name.endsWith(".json"));
        DriveClient.require(files != null && files.length <= 256, "Invalid revision journal");
        Arrays.sort(files);
        return new ArrayList<>(Arrays.asList(files));
    }

    private static JSONObject read(File file) throws Exception {
        DriveClient.require(file.length() <= 32768, "Journal record exceeds limit");
        return new JSONObject(new String(readBytes(file), StandardCharsets.UTF_8));
    }

    private static byte[] readBytes(File file) throws Exception {
        return DriveClient.read(new java.io.FileInputStream(file));
    }

    static void write(File destination, byte[] bytes) throws Exception {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        Files.move(temporary.toPath(), destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(destination.getParentFile());
    }

    static void syncDirectory(File directory) throws Exception {
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
