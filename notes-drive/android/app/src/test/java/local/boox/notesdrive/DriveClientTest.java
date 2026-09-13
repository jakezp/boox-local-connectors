package local.boox.notesdrive;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class DriveClientTest {
    @Test public void authorizationGrantUsesGoogleStringScopesAndRequiresToken() {
        com.google.android.gms.auth.api.identity.AuthorizationResult granted =
            new com.google.android.gms.auth.api.identity.AuthorizationResult(null, "test-token", null,
                java.util.Collections.singletonList(DriveSession.SCOPE), null, null);
        assertTrue(DriveSession.hasDriveGrant(granted));
        com.google.android.gms.auth.api.identity.AuthorizationResult missingScope =
            new com.google.android.gms.auth.api.identity.AuthorizationResult(null, "test-token", null,
                java.util.Collections.singletonList("openid"), null, null);
        assertFalse(DriveSession.hasDriveGrant(missingScope));
        com.google.android.gms.auth.api.identity.AuthorizationResult missingToken =
            new com.google.android.gms.auth.api.identity.AuthorizationResult(null, null, null,
                java.util.Collections.singletonList(DriveSession.SCOPE), null, null);
        assertFalse(DriveSession.hasDriveGrant(missingToken));
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject folder(String id) throws Exception {
        return new JSONObject().put("id", id).put("name", "BOOX Notes Sync")
            .put("mimeType", DriveClient.FOLDER).put("trashed", false)
            .put("capabilities", new JSONObject().put("canEdit", true))
            .put("appProperties", new JSONObject().put(DriveClient.MARKER, "1"));
    }
    private static class Server implements DriveClient.Transport {
        final byte[] payload = bytes("disposable native archive bytes");
        final List<String> calls = new ArrayList<>();
        boolean corruptDownload, wrongParent;
        public byte[] request(String method, String url, String type, byte[] body) throws Exception {
            calls.add(method + " " + url);
            if (url.contains("files/folder-id?fields=")) return bytes(folder("folder-id").toString());
            if (method.equals("POST") && url.contains("/upload/")) {
                String multipart = new String(body, StandardCharsets.UTF_8);
                int start = multipart.indexOf("\r\n\r\n") + 4;
                JSONObject metadata = new JSONObject(multipart.substring(start, multipart.indexOf("\r\n--", start)));
                return bytes(new JSONObject().put("id", "test-file")
                    .put("parents", new JSONArray().put(wrongParent ? "another-folder" : "folder-id"))
                    .put("appProperties", metadata.getJSONObject("appProperties"))
                    .put("size", payload.length).put("md5Checksum", DriveClient.digest("MD5", payload)).toString());
            }
            if (url.endsWith("files/test-file?alt=media"))
                return corruptDownload ? bytes("different contents") : payload;
            if (method.equals("PATCH") && url.contains("files/test-file?")) {
                assertTrue(new JSONObject(new String(body, StandardCharsets.UTF_8)).getBoolean("trashed"));
                return bytes(new JSONObject().put("id", "test-file").put("trashed", true).toString());
            }
            throw new AssertionError("Unexpected request: " + method + " " + url);
        }
    }

    @Test public void completeRoundTripTrashesOnlyTheVerifiedTestFile() throws Exception {
        Server server = new Server();
        assertEquals(DriveClient.digest("SHA-256", server.payload),
            new DriveClient(server).roundTrip("folder-id", server.payload));
        assertEquals(4, server.calls.size());
        assertTrue(server.calls.get(3).startsWith("PATCH " + DriveClient.API + "files/test-file?"));
        assertFalse(server.calls.stream().anyMatch(c -> c.startsWith("DELETE")));
    }
    @Test public void damagedDownloadCannotPassOrTriggerCleanup() throws Exception {
        Server server = new Server();
        server.corruptDownload = true;
        assertThrows(IOException.class, () -> new DriveClient(server).roundTrip("folder-id", server.payload));
        assertEquals(3, server.calls.size());
    }
    @Test public void unexpectedUploadParentCannotBeDownloadedOrDeleted() throws Exception {
        Server server = new Server();
        server.wrongParent = true;
        assertThrows(IOException.class, () -> new DriveClient(server).roundTrip("folder-id", server.payload));
        assertEquals(2, server.calls.size());
    }
    @Test public void unmarkedDirectoryIsRefused() throws Exception {
        JSONObject file = folder("opaque-id").put("appProperties", new JSONObject());
        assertThrows(IOException.class, () -> DriveClient.folder(file));
    }
    @Test public void readOnlyAndTrashedDirectoriesAreRefused() {
        assertThrows(IOException.class, () -> DriveClient.folder(folder("id").put("trashed", true)));
        assertThrows(IOException.class, () -> DriveClient.folder(folder("id")
            .put("capabilities", new JSONObject().put("canEdit", false))));
    }
    @Test public void folderPaginationRetainsDistinctIdsAndDeduplicatesRepeats() throws Exception {
        int[] pages = {0};
        DriveClient client = new DriveClient((method, url, type, body) -> {
            int page = pages[0]++;
            assertEquals("GET", method);
            if (page == 0) return bytes(new JSONObject().put("nextPageToken", "opaque + / token")
                .put("files", new JSONArray().put(folder("first"))).toString());
            assertTrue(url.contains("pageToken=opaque+%2B+%2F+token"));
            return bytes(new JSONObject().put("files",
                new JSONArray().put(folder("first")).put(folder("second"))).toString());
        });
        assertEquals(2, client.folders().size());
        assertEquals(2, pages[0]);
    }
    @Test public void repeatedPaginationTokenAndIncompleteSearchAreRefused() {
        DriveClient repeated = new DriveClient((m, u, t, b) ->
            bytes("{\"nextPageToken\":\"repeat\",\"files\":[]}"));
        assertThrows(IOException.class, repeated::folders);
        DriveClient incomplete = new DriveClient((m, u, t, b) ->
            bytes("{\"incompleteSearch\":true,\"files\":[]}"));
        assertThrows(IOException.class, incomplete::folders);
    }
    @Test public void emptyFolderListCausesNoMutation() throws Exception {
        DriveClient client = new DriveClient((method, url, type, body) -> {
            assertEquals("GET", method);
            return bytes("{\"files\":[]}");
        });
        assertTrue(client.folders().isEmpty());
    }
    @Test public void unsafeIdsAndOversizedResponseAreRefused() {
        assertThrows(IOException.class, () -> DriveClient.fileId("../other?alt=media"));
        assertThrows(IOException.class, () -> DriveClient.read(
            new java.io.ByteArrayInputStream(new byte[DriveClient.MAX_BYTES + 1])));
    }
}
