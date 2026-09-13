package local.boox.notesdrive;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RevisionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static String hash(byte[] bytes) throws Exception { return DriveClient.digest("SHA-256", bytes); }
    static Revision revision(String device, byte[] payload, String... parents) throws Exception {
        return new Revision("notebook", device, hash(payload), Arrays.asList(parents));
    }
    static Map<String, byte[]> records(Revision... revisions) throws Exception {
        Map<String, byte[]> records = new HashMap<>();
        for (Revision revision : revisions) records.put(revision.id(), revision.encode());
        return records;
    }

    @Test public void encodingMatchesPythonWireFormat() throws Exception {
        Revision revision = revision("android-test", bytes("hello"));
        assertEquals("{\"deleted\":false,\"device\":\"android-test\",\"notebook\":\"notebook\"," +
            "\"parents\":[],\"payload\":\"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\",\"schema\":1}",
            new String(revision.encode(), StandardCharsets.UTF_8));
        assertEquals(revision.id(), Revision.decode(revision.encode()).id());
    }

    @Test public void ambiguousJsonAndWrongTypesAreRejected() throws Exception {
        String canonical = new String(revision("device", bytes("hello")).encode(), StandardCharsets.UTF_8);
        for (String changed : Arrays.asList(canonical + " ", canonical.replace("\"schema\":1", "\"schema\":1.0"),
                canonical.replace("\"schema\":1", "\"schema\":true"),
                canonical.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
                canonical.replace("\"deleted\":false", "\"deleted\":\"false\""),
                canonical.replace("\"deleted\":false", "\"deleted\":true"),
                canonical.replace("\"device\":\"device\"", "\"device\":\"../device\"")))
            assertThrows(Exception.class, () -> Revision.decode(bytes(changed)));
    }

    @Test public void independentEditsRemainTwoHeadsAndExplicitMergeHasOneHead() throws Exception {
        byte[] payload = bytes("snapshot");
        Revision first = revision("android", payload), left = revision("android", payload, first.id()),
            right = revision("mac", payload, first.id());
        RevisionCatalog conflict = new RevisionCatalog(records(first, left, right),
            Collections.singletonMap(hash(payload), payload));
        assertEquals(2, conflict.heads("notebook").size());
        Revision merge = revision("mac", payload, left.id(), right.id());
        assertEquals(Collections.singletonList(merge.id()),
            new RevisionCatalog(records(first, left, right, merge),
                Collections.singletonMap(hash(payload), payload)).heads("notebook"));
    }

    @Test public void incompleteAndForeignAncestryCannotBecomeUsableCatalog() throws Exception {
        byte[] payload = bytes("snapshot");
        Revision first = revision("android", payload);
        Revision child = revision("mac", payload, first.id());
        Map<String, byte[]> payloads = Collections.singletonMap(hash(payload), payload);
        assertThrows(IOException.class, () -> new RevisionCatalog(records(child), payloads));
        Revision foreign = new Revision("another-notebook", "mac", hash(payload), Collections.singletonList(first.id()));
        assertThrows(IOException.class, () -> new RevisionCatalog(records(first, foreign), payloads));
        assertThrows(IOException.class, () -> new RevisionCatalog(records(first), Collections.emptyMap()));
        assertThrows(IOException.class, () -> new RevisionCatalog(records(first),
            Collections.singletonMap(hash(payload), bytes("corrupt"))));
    }

    @Test public void queueSurvivesFailureAndNewInstanceWithoutChangingRevision() throws Exception {
        File root = temp.newFolder();
        byte[] payload = bytes("snapshot");
        Revision revision = revision("android", payload);
        RevisionQueue queue = new RevisionQueue(root);
        queue.enqueue("account", "folder", revision, payload);
        assertThrows(IOException.class, () -> queue.retry("account", "folder", (r, p) -> {
            assertEquals(revision.id(), r.id());
            throw new IOException("interrupted");
        }));
        RevisionQueue restarted = new RevisionQueue(root);
        assertEquals(1, restarted.pending());
        assertEquals(1, restarted.retry("account", "folder", (r, p) -> {
            assertEquals(revision.id(), r.id()); assertArrayEquals(payload, p);
        }));
        assertEquals(0, new RevisionQueue(root).pending());
        assertEquals(0, restarted.retry("account", "folder", (r, p) -> fail("already verified")));
    }

    @Test public void wrongAccountOrFolderDoesNotRedirectQueuedData() throws Exception {
        RevisionQueue queue = new RevisionQueue(temp.newFolder());
        queue.enqueue("account", "folder", revision("android", bytes("snapshot")), bytes("snapshot"));
        assertThrows(IOException.class, () -> queue.retry("other-account", "folder", (r, p) -> fail()));
        assertThrows(IOException.class, () -> queue.retry("account", "other-folder", (r, p) -> fail()));
        assertEquals(1, queue.pending());
    }

    @Test public void damagedLocalPayloadIsNotPublished() throws Exception {
        File root = temp.newFolder();
        byte[] payload = bytes("snapshot");
        RevisionQueue queue = new RevisionQueue(root);
        queue.enqueue("account", "folder", revision("android", payload), payload);
        Files.write(new File(root, "payloads/" + hash(payload) + ".note").toPath(), bytes("damaged"));
        assertThrows(IOException.class, () -> queue.retry("account", "folder", (r, p) -> fail()));
        assertEquals(1, queue.pending());
    }

    static final class Server implements DriveClient.Transport {
        final Map<String, JSONObject> metadata = new LinkedHashMap<>();
        final Map<String, byte[]> data = new LinkedHashMap<>();
        int posts, downloads;
        String failAfterType;
        boolean corruptDownload, incomplete, repeatPage, wrongParent;

        JSONObject add(String type, byte[] bytes) throws Exception {
            String id = "object-" + metadata.size();
            JSONObject file = new JSONObject().put("id", id).put("parents", new JSONArray().put("folder"))
                .put("trashed", false).put("size", bytes.length).put("md5Checksum", DriveClient.digest("MD5", bytes))
                .put("appProperties", new JSONObject().put("booxNotesProtocol", "1")
                    .put("booxObjectType", type).put("booxObjectSha256", hash(bytes)));
            metadata.put(id, file); data.put(id, bytes);
            return file;
        }

        public byte[] request(String method, String url, String type, byte[] body) throws Exception {
            if (method.equals("GET") && url.contains("files/folder?fields="))
                return bytes(new JSONObject().put("id", "folder").put("name", "BOOX Notes Sync")
                    .put("mimeType", DriveClient.FOLDER).put("trashed", false)
                    .put("capabilities", new JSONObject().put("canEdit", true))
                    .put("appProperties", new JSONObject().put("booxNotesProtocol", "1")).toString());
            if (method.equals("GET") && url.contains("/files?spaces=")) {
                JSONObject page = new JSONObject().put("files", new JSONArray(new ArrayList<>(metadata.values())))
                    .put("incompleteSearch", incomplete);
                if (repeatPage) page.put("nextPageToken", "again");
                return bytes(page.toString());
            }
            if (method.equals("GET") && url.endsWith("?alt=media")) {
                downloads++;
                String id = url.substring(url.indexOf("files/") + 6, url.indexOf("?alt="));
                return corruptDownload ? bytes("damaged") : data.get(id);
            }
            if (method.equals("POST") && url.contains("/upload/")) {
                posts++;
                String multipart = new String(body, StandardCharsets.UTF_8);
                int start = multipart.indexOf("\r\n\r\n") + 4;
                JSONObject meta = new JSONObject(multipart.substring(start, multipart.indexOf("\r\n--", start)));
                int dataStart = multipart.indexOf("\r\n\r\n", multipart.indexOf("\r\n--", start)) + 4;
                byte[] payload = bytes(multipart.substring(dataStart, multipart.lastIndexOf("\r\n--")));
                String objectType = meta.getJSONObject("appProperties").getString("booxObjectType");
                JSONObject result = add(objectType, payload);
                if (wrongParent) result.put("parents", new JSONArray().put("elsewhere"));
                if (objectType.equals(failAfterType)) {
                    failAfterType = null;
                    throw new IOException("response lost after server commit");
                }
                return bytes(result.toString());
            }
            throw new AssertionError("Unexpected write/request: " + method + " " + url);
        }
    }

    @Test public void verifiedCacheReusesOnlyUnchangedDriveIdsAndVersions() throws Exception {
        Server server = new Server();
        byte[] payload = bytes("snapshot");
        server.add("payload", payload).put("version", "1");
        server.add("revision", revision("android", payload).encode()).put("version", "1");
        File cache = temp.newFolder();
        new RevisionDriveStore(new DriveClient(server), "folder", cache).load();
        assertEquals(2, server.downloads);
        new RevisionDriveStore(new DriveClient(server), "folder", cache).load();
        assertEquals(2, server.downloads);
        server.metadata.get("object-0").put("version", "2");
        new RevisionDriveStore(new DriveClient(server), "folder", cache).load();
        assertEquals(3, server.downloads);
        JSONObject duplicate = server.add("payload", payload).put("version", "1");
        server.data.put(duplicate.getString("id"), bytes("damaged"));
        assertThrows(IOException.class, () -> new RevisionDriveStore(new DriveClient(server), "folder", cache).load());
    }

    @Test public void damagedPersistentCacheCannotProduceAVerifiedCatalog() throws Exception {
        Server server = new Server();
        byte[] payload = bytes("snapshot");
        server.add("payload", payload).put("version", "1");
        server.add("revision", revision("android", payload).encode()).put("version", "1");
        File cache = temp.newFolder();
        new RevisionDriveStore(new DriveClient(server), "folder", cache).load();
        File saved = cache.listFiles()[0];
        Files.write(saved.toPath(), bytes("damaged"));
        assertThrows(IOException.class, () -> new RevisionDriveStore(new DriveClient(server), "folder", cache).load());
    }

    @Test public void retryAfterUnknownPayloadCommitDoesNotCreateDuplicate() throws Exception {
        Server server = new Server();
        server.failAfterType = "payload";
        RevisionDriveStore store = new RevisionDriveStore(new DriveClient(server), "folder");
        byte[] payload = bytes("snapshot");
        Revision revision = revision("android", payload);
        assertThrows(IOException.class, () -> store.publish(revision, payload));
        assertEquals(1, server.posts);
        store.publish(revision, payload);
        assertEquals(2, server.posts);
        assertEquals(Collections.singletonList(revision.id()), store.load().heads("notebook"));
    }

    @Test public void retryAfterUnknownRevisionCommitDoesNotRepublish() throws Exception {
        Server server = new Server();
        server.failAfterType = "revision";
        RevisionDriveStore store = new RevisionDriveStore(new DriveClient(server), "folder");
        byte[] payload = bytes("snapshot");
        Revision revision = revision("android", payload);
        assertThrows(IOException.class, () -> store.publish(revision, payload));
        assertEquals(2, server.posts);
        store.publish(revision, payload);
        assertEquals(2, server.posts);
    }

    @Test public void validDuplicatesAreAcceptedButCorruptDuplicateBlocksCatalog() throws Exception {
        Server server = new Server();
        byte[] payload = bytes("snapshot");
        server.add("payload", payload);
        server.add("payload", payload);
        server.add("revision", revision("android", payload).encode());
        RevisionDriveStore store = new RevisionDriveStore(new DriveClient(server), "folder");
        assertEquals(1, store.load().heads("notebook").size());
        server.data.put("object-1", bytes("damaged"));
        assertThrows(IOException.class, store::load);
    }

    @Test public void incompleteOrRepeatingListingNeverPublishes() throws Exception {
        Server server = new Server();
        RevisionDriveStore store = new RevisionDriveStore(new DriveClient(server), "folder");
        byte[] payload = bytes("snapshot");
        Revision revision = revision("android", payload);
        server.incomplete = true;
        assertThrows(IOException.class, () -> store.publish(revision, payload));
        server.incomplete = false; server.repeatPage = true;
        assertThrows(IOException.class, store::load);
        assertEquals(0, server.posts);
    }

    @Test public void failedPayloadVerificationDoesNotPublishRevision() throws Exception {
        for (boolean wrongParent : Arrays.asList(false, true)) {
            Server server = new Server();
            server.wrongParent = wrongParent;
            server.corruptDownload = !wrongParent;
            RevisionDriveStore store = new RevisionDriveStore(new DriveClient(server), "folder");
            byte[] payload = bytes("snapshot");
            assertThrows(IOException.class, () -> store.publish(revision("android", payload), payload));
            assertEquals(1, server.posts);
            assertEquals("payload", server.metadata.get("object-0").getJSONObject("appProperties")
                .getString("booxObjectType"));
        }
    }
}
