package local.boox.notesdrive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded Drive connection test. No native Notes access or sync scheduling. */
public final class DriveClient {
    static final String API = "https://www.googleapis.com/drive/v3/";
    static final String FOLDER = "application/vnd.google-apps.folder";
    static final String MARKER = "booxNotesProtocol";
    static final int MAX_BYTES = 4 * 1024 * 1024;
    interface Transport {
        byte[] request(String method, String url, String contentType, byte[] body) throws Exception;
    }
    public static final class Unauthorized extends IOException {}
    public static final class Folder {
        public final String id, name;
        Folder(String id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name + " · " + id.substring(0, Math.min(8, id.length())); }
    }
    private final Transport transport;

    public DriveClient(String token) { this(new HttpTransport(token)); }
    DriveClient(Transport transport) { this.transport = transport; }

    byte[] request(String method, String url, String type, byte[] body) throws Exception {
        return transport.request(method, url, type, body);
    }

    public JSONObject account() throws Exception {
        JSONObject user = get("about?fields=user(permissionId,emailAddress,displayName)").getJSONObject("user");
        require(!user.optString("permissionId").isEmpty(), "Drive account identity is unavailable");
        return user;
    }

    public List<Folder> folders() throws Exception {
        List<Folder> result = new ArrayList<>();
        Set<String> ids = new HashSet<>(), tokens = new HashSet<>();
        String token = "";
        String query = "trashed=false and mimeType='" + FOLDER +
            "' and appProperties has { key='" + MARKER + "' and value='1' }";
        do {
            require(tokens.add(token) && tokens.size() <= 100, "Drive listing could not be completed");
            JSONObject page = get("files?spaces=drive&pageSize=100&q=" + encode(query) +
                "&fields=nextPageToken,incompleteSearch,files(id,name,mimeType,trashed,capabilities(canEdit),appProperties)" +
                (token.isEmpty() ? "" : "&pageToken=" + encode(token)));
            require(!page.optBoolean("incompleteSearch"), "Drive returned an incomplete folder search");
            JSONArray files = page.getJSONArray("files");
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                Folder folder = folder(file);
                if (ids.add(folder.id)) result.add(folder);
                require(result.size() <= 1000, "Too many sync directories");
            }
            token = page.optString("nextPageToken");
        } while (!token.isEmpty());
        return result;
    }

    public Folder createFolder() throws Exception {
        JSONObject body = new JSONObject().put("name", "BOOX Notes Sync").put("mimeType", FOLDER)
            .put("appProperties", new JSONObject().put(MARKER, "1"));
        JSONObject created = json("POST", API + "files?fields=id", body);
        return checkFolder(created.getString("id"));
    }

    public Folder checkFolder(String id) throws Exception {
        return folder(get("files/" + fileId(id) +
            "?fields=id,name,mimeType,trashed,capabilities(canEdit),appProperties"));
    }

    public String roundTrip(String folderId, byte[] payload) throws Exception {
        require(payload.length > 0 && payload.length <= MAX_BYTES, "Connection fixture is too large");
        checkFolder(folderId);
        String nonce = UUID.randomUUID().toString();
        String boundary = "boox_" + nonce;
        JSONObject metadata = new JSONObject()
            .put("name", "BOOX connection test " + nonce + ".note")
            .put("parents", new JSONArray().put(folderId))
            .put("appProperties", new JSONObject().put("booxConnectionTest", nonce));
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
            metadata + "\r\n--" + boundary + "\r\nContent-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
        multipart.write(payload);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        JSONObject uploaded = new JSONObject(new String(transport.request("POST",
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,parents,md5Checksum,size,appProperties",
            "multipart/related; boundary=" + boundary, multipart.toByteArray()), StandardCharsets.UTF_8));
        String id = fileId(uploaded.getString("id"));
        JSONArray parents = uploaded.getJSONArray("parents");
        require(parents.length() == 1 && folderId.equals(parents.getString(0)), "Uploaded test folder differs");
        require(nonce.equals(uploaded.getJSONObject("appProperties").optString("booxConnectionTest")),
            "Uploaded test identity differs");
        require(uploaded.getLong("size") == payload.length &&
            digest("MD5", payload).equals(uploaded.getString("md5Checksum")), "Drive upload checksum differs");
        byte[] downloaded = transport.request("GET", API + "files/" + id + "?alt=media", null, null);
        String hash = digest("SHA-256", payload);
        require(hash.equals(digest("SHA-256", downloaded)), "Downloaded notebook checksum differs");
        // Only this newly created and verified disposable file is moved to Trash.
        JSONObject trashed = json("PATCH", API + "files/" + id + "?fields=id,trashed",
            new JSONObject().put("trashed", true));
        require(id.equals(trashed.getString("id")) && trashed.getBoolean("trashed"),
            "Test passed, but the disposable Drive file still needs cleanup");
        return hash;
    }

    static Folder folder(JSONObject file) throws Exception {
        require(FOLDER.equals(file.optString("mimeType")) && !file.optBoolean("trashed"),
            "Sync directory is missing or in Trash");
        require(file.optJSONObject("capabilities") != null &&
            file.getJSONObject("capabilities").optBoolean("canEdit"), "Sync directory is not writable");
        require(file.optJSONObject("appProperties") != null &&
            "1".equals(file.getJSONObject("appProperties").optString(MARKER)), "Unrecognized sync directory");
        return new Folder(fileId(file.getString("id")), file.getString("name"));
    }

    private JSONObject get(String path) throws Exception {
        return new JSONObject(new String(transport.request("GET", API + path, null, null),
            StandardCharsets.UTF_8));
    }
    private JSONObject json(String method, String url, JSONObject body) throws Exception {
        return new JSONObject(new String(transport.request(method, url, "application/json; charset=UTF-8",
            body.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
    }
    static String fileId(String value) throws IOException {
        require(value.matches("[A-Za-z0-9_-]{1,256}"), "Invalid Drive file ID");
        return value;
    }
    static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
    static String digest(String algorithm, byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance(algorithm).digest(bytes))
            result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    static byte[] read(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = source.read(chunk)) != -1) {
                require(output.size() + count <= MAX_BYTES, "Drive response exceeds the test limit");
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }
    private static final class HttpTransport implements Transport {
        private final String token;
        HttpTransport(String token) { this.token = token; }
        public byte[] request(String method, String url, String type, byte[] body) throws Exception {
            URL destination = new URL(url);
            require("https".equals(destination.getProtocol()) &&
                "www.googleapis.com".equals(destination.getHost()), "Unexpected Drive destination");
            HttpURLConnection connection = (HttpURLConnection) destination.openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", type);
                    connection.setFixedLengthStreamingMode(body.length);
                    try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
                }
                int status = connection.getResponseCode();
                if (status == 401) throw new Unauthorized();
                require(status >= 200 && status < 300, "Drive request failed (HTTP " + status + ")");
                return read(connection.getInputStream());
            } finally { connection.disconnect(); }
        }
    }
}
