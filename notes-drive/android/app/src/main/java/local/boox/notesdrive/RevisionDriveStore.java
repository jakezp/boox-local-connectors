package local.boox.notesdrive;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable, bounded revision transport. It never overwrites or deletes remote objects. */
final class RevisionDriveStore {
    private final DriveClient client;
    private final String folder;
    private final java.io.File cache;
    private long downloaded;

    RevisionDriveStore(DriveClient client, String folder) throws Exception {
        this(client, folder, null);
    }

    RevisionDriveStore(DriveClient client, String folder, java.io.File cache) throws Exception {
        this.client = client;
        this.folder = DriveClient.fileId(folder);
        this.cache = cache;
        if (cache != null)
            DriveClient.require(cache.isDirectory() || cache.mkdirs(), "Cannot create verified Drive cache");
    }

    private static String key(String type, String hash) { return type + ":" + hash; }

    private List<JSONObject> list() throws Exception {
        client.checkFolder(folder);
        List<JSONObject> result = new ArrayList<>();
        Set<String> pages = new HashSet<>(), ids = new HashSet<>();
        String page = "";
        String query = "'" + folder + "' in parents and trashed=false and appProperties has " +
            "{ key='booxNotesProtocol' and value='1' }";
        do {
            DriveClient.require(pages.add(page) && pages.size() <= 100, "Incomplete revision listing");
            JSONObject response = json(client.request("GET", DriveClient.API +
                "files?spaces=drive&pageSize=100&q=" + DriveClient.encode(query) +
                "&fields=nextPageToken,incompleteSearch,files(id,version,parents,size,md5Checksum,trashed,appProperties)" +
                (page.isEmpty() ? "" : "&pageToken=" + DriveClient.encode(page)), null, null));
            DriveClient.require(!response.optBoolean("incompleteSearch"), "Incomplete revision search");
            JSONArray files = response.getJSONArray("files");
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                validateMetadata(file);
                if (ids.add(file.getString("id"))) result.add(file);
                DriveClient.require(result.size() <= 1000, "Revision directory exceeds preview limit");
            }
            page = response.optString("nextPageToken");
        } while (!page.isEmpty());
        return result;
    }

    private String validateMetadata(JSONObject file) throws Exception {
        DriveClient.fileId(file.getString("id"));
        JSONArray parents = file.getJSONArray("parents");
        JSONObject properties = file.getJSONObject("appProperties");
        String type = properties.getString("booxObjectType"), hash = properties.getString("booxObjectSha256");
        DriveClient.require(!file.optBoolean("trashed") && parents.length() == 1 &&
            folder.equals(parents.getString(0)) && "1".equals(properties.optString("booxNotesProtocol")) &&
            ("payload".equals(type) || "revision".equals(type)) && Revision.hash(hash),
            "Invalid revision object metadata");
        long size = file.getLong("size");
        DriveClient.require(size > 0 && size <= ("payload".equals(type) ? DriveClient.MAX_BYTES : 16384),
            "Revision object exceeds limit");
        return key(type, hash);
    }

    private byte[] download(JSONObject file) throws Exception {
        String objectKey = validateMetadata(file);
        downloaded += file.getLong("size");
        DriveClient.require(downloaded <= 64L * 1024 * 1024, "Revision refresh exceeds download limit");
        java.io.File cached = cacheFile(file);
        if (cached != null && cached.exists()) {
            byte[] bytes = DriveClient.read(new java.io.FileInputStream(cached));
            verifyBytes(file, objectKey, bytes);
            return bytes;
        }
        byte[] bytes = client.request("GET", DriveClient.API + "files/" +
            DriveClient.fileId(file.getString("id")) + "?alt=media", null, null);
        verifyBytes(file, objectKey, bytes);
        if (cached != null) RevisionQueue.write(cached, bytes);
        return bytes;
    }

    private void verifyBytes(JSONObject file, String objectKey, byte[] bytes) throws Exception {
        DriveClient.require(bytes.length == file.getLong("size") &&
            objectKey.endsWith(":" + DriveClient.digest("SHA-256", bytes)) &&
            DriveClient.digest("MD5", bytes).equals(file.getString("md5Checksum")),
            "Revision object checksum differs");
    }

    private java.io.File cacheFile(JSONObject file) throws Exception {
        // A separate receipt per Drive ID and version means new duplicates cannot borrow trust.
        String version = file.optString("version", "");
        if (cache == null || version.isEmpty()) return null;
        String identity = folder + ":" + file.getString("id") + ":" + version + ":" +
            validateMetadata(file) + ":" + file.getLong("size") + ":" + file.getString("md5Checksum");
        return new java.io.File(cache, DriveClient.digest("SHA-256",
            identity.getBytes(StandardCharsets.UTF_8)) + ".bin");
    }

    RevisionCatalog load() throws Exception {
        downloaded = 0;
        Map<String, byte[]> records = new HashMap<>(), payloads = new HashMap<>();
        Set<String> keep = new HashSet<>();
        for (JSONObject file : list()) {
            JSONObject properties = file.getJSONObject("appProperties");
            byte[] bytes = download(file); // Validate every matching ID, including duplicate logical objects.
            java.io.File cached = cacheFile(file);
            if (cached != null) keep.add(cached.getName());
            String hash = properties.getString("booxObjectSha256");
            if ("revision".equals(properties.getString("booxObjectType"))) records.put(hash, bytes);
            else payloads.put(hash, bytes);
        }
        if (cache != null) {
            java.io.File[] files = cache.listFiles((directory, name) -> name.endsWith(".bin"));
            if (files != null) for (java.io.File file : files)
                if (!keep.contains(file.getName())) java.nio.file.Files.delete(file.toPath());
        }
        return new RevisionCatalog(records, payloads);
    }

    void publish(Revision revision, byte[] payload) throws Exception {
        DriveClient.require(revision.payload == null ? payload == null : payload != null &&
            payload.length > 0 && payload.length <= DriveClient.MAX_BYTES &&
            revision.payload.equals(DriveClient.digest("SHA-256", payload)), "Queued payload differs");
        RevisionCatalog before = load();
        for (String parent : revision.parents)
            DriveClient.require(before.revisions.containsKey(parent) &&
                revision.notebook.equals(before.revisions.get(parent).notebook), "Queued parent is missing");
        if (revision.payload != null) ensure("payload", revision.payload, payload);
        ensure("revision", revision.id(), revision.encode());
        // Do not report publication success on an incomplete/malformed catalog.
        DriveClient.require(load().revisions.containsKey(revision.id()), "Published revision is not visible");
    }

    private void ensure(String type, String hash, byte[] bytes) throws Exception {
        downloaded = 0;
        boolean found = false;
        for (JSONObject file : list()) {
            if (key(type, hash).equals(validateMetadata(file))) {
                download(file);
                found = true;
            }
        }
        if (found) return;
        String boundary = "boox_" + UUID.randomUUID();
        JSONObject metadata = new JSONObject()
            .put("name", type + "-" + hash + ("payload".equals(type) ? ".note" : ".json"))
            .put("parents", new JSONArray().put(folder))
            .put("appProperties", new JSONObject().put("booxNotesProtocol", "1")
                .put("booxObjectType", type).put("booxObjectSha256", hash));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
            metadata + "\r\n--" + boundary + "\r\nContent-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        JSONObject uploaded = json(client.request("POST",
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart" +
            "&fields=id,parents,size,md5Checksum,trashed,appProperties",
            "multipart/related; boundary=" + boundary, out.toByteArray()));
        DriveClient.require(key(type, hash).equals(validateMetadata(uploaded)), "Uploaded object identity differs");
        download(uploaded);
    }

    private static JSONObject json(byte[] bytes) throws Exception {
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
}
