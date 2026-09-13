package local.boox.notesdrive;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NativeInboxTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private byte[] archive(String value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("book/note/pb/note_info"));
            zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    private Revision revision(byte[] bytes, String... parents) throws Exception {
        return new Revision("boox-book", "device", DriveClient.digest("SHA-256", bytes), Arrays.asList(parents));
    }
    private void catalog(File files, List<Revision> revisions, byte[]... payloads) throws Exception {
        String binding = DriveClient.digest("SHA-256", "account:folder".getBytes(StandardCharsets.UTF_8));
        File dir = new File(files, "incoming/" + binding); assertTrue(dir.mkdirs() || dir.isDirectory());
        JSONArray ids = new JSONArray();
        for (Revision revision : revisions) {
            ids.put(revision.id());
            RevisionQueue.write(new File(dir, "revision-" + revision.id() + ".json"), revision.encode());
        }
        for (byte[] bytes : payloads) RevisionQueue.write(new File(dir, "payload-" +
            DriveClient.digest("SHA-256", bytes) + ".note"), bytes);
        JSONObject snapshot = new JSONObject().put("schema", 1).put("account", "account")
            .put("folder", "folder").put("revisions", ids);
        RevisionQueue.write(new File(dir, "catalog.json"), snapshot.toString().getBytes(StandardCharsets.UTF_8));
    }
    private Revision local(File files, byte[] bytes) throws Exception {
        LibraryQueue library = new LibraryQueue(new File(files, "library"));
        library.capture("account", "folder", "book", "Book", bytes);
        library.stage("account", "folder", "device", new RevisionQueue(new File(files, "outgoing")));
        return revision(bytes);
    }
    @Test public void uniqueRemoteDescendantCanAdvanceCleanLocalBranch() throws Exception {
        File files = temp.newFolder();
        byte[] first = archive("first"), next = archive("next");
        Revision base = local(files, first), head = revision(next, base.id());
        catalog(files, Arrays.asList(base, head), first, next);
        JSONObject offer = new NativeInbox(files, "account", "folder").offers().get(0);
        assertEquals("ready", offer.getString("status"));
        assertEquals(base.id(), offer.getString("base"));
        assertEquals(head.id(), offer.getString("revision"));
    }
    @Test public void emptyDeviceOffersEveryStoredNotebookAndFolderWithoutLocalHistory() throws Exception {
        File files = temp.newFolder();
        byte[] first = archive("first"), second = archive("second");
        byte[] folder = new FolderRecord("parent", null, "Notebooks").encode();
        Revision old = revision(first);
        Revision current = revision(second, old.id());
        Revision other = new Revision("boox-other", "other-boox",
            DriveClient.digest("SHA-256", first), Collections.emptyList());
        Revision directory = new Revision("folder-parent", "other-boox",
            DriveClient.digest("SHA-256", folder), Collections.emptyList());
        catalog(files, Arrays.asList(old, current, other, directory), first, second, folder);
        List<JSONObject> offers = new NativeInbox(files, "account", "folder").offers();
        assertEquals(3, offers.size());
        java.util.Set<String> revisions = new java.util.HashSet<>();
        for (JSONObject offer : offers) {
            assertEquals("ready", offer.getString("status"));
            assertEquals("", offer.getString("base"));
            revisions.add(offer.getString("revision"));
        }
        assertEquals(new java.util.HashSet<>(Arrays.asList(current.id(), other.id(), directory.id())), revisions);
        assertEquals("Stored notebooks: 2 · Folders: 1\nPending uploads: 0 · Items needing conflict review: 0",
            DriveSession.catalogSummary(new NativeInbox(files, "account", "folder").catalog, 0));
    }
    @Test public void pendingLocalCaptureIsNeverOverwritten() throws Exception {
        File files = temp.newFolder();
        byte[] first = archive("first"), next = archive("next");
        Revision base = local(files, first), head = revision(next, base.id());
        new LibraryQueue(new File(files, "library")).capture("account", "folder", "book", "Book", archive("local"));
        catalog(files, Arrays.asList(base, head), first, next);
        assertEquals("local-pending", new NativeInbox(files, "account", "folder").offers().get(0).getString("status"));
    }
    @Test public void concurrentHeadsRequireReviewAndDoNotSelectByTimestamp() throws Exception {
        File files = temp.newFolder();
        byte[] first = archive("first"), left = archive("left"), right = archive("right");
        Revision base = local(files, first), a = revision(left, base.id()), b = revision(right, base.id());
        catalog(files, Arrays.asList(base, a, b), first, left, right);
        assertEquals("conflict", new NativeInbox(files, "account", "folder").offers().get(0).getString("status"));
    }
    @Test public void nativeAdoptionDoesNotEchoAndNextEditParentsTheRemoteRevision() throws Exception {
        File files = temp.newFolder();
        byte[] first = archive("first"), next = archive("next"), readback = archive("native-wrapper");
        Revision base = local(files, first), head = revision(next, base.id());
        LibraryQueue library = new LibraryQueue(new File(files, "library"));
        library.adopt("account", "folder", base.id(), head, next, readback, "Book");
        RevisionQueue queue = new RevisionQueue(new File(files, "next-outgoing"));
        assertEquals(0, library.stage("account", "folder", "device", queue));
        assertTrue(library.isApplied("boox-book", head.id()));
        library.adopt("account", "folder", base.id(), head, next, readback, "Book");
        library.capture("account", "folder", "book", "Book", archive("later"));
        assertEquals(1, library.stage("account", "folder", "device", queue));
        queue.retry("account", "folder", (revision, bytes) -> assertEquals(Collections.singletonList(head.id()), revision.parents));
    }
    @Test public void staleNativeAcknowledgmentCannotAdvanceChangedLocalBranch() throws Exception {
        File files = temp.newFolder();
        byte[] first = archive("first"), next = archive("next");
        Revision base = local(files, first), head = revision(next, base.id());
        LibraryQueue library = new LibraryQueue(new File(files, "library"));
        library.capture("account", "folder", "book", "Book", archive("local edit"));
        assertThrows(Exception.class, () -> library.adopt("account", "folder", base.id(), head, next, next, "Book"));
    }
    @Test public void interruptedAdoptionReplaysBothCaptureAndBranchBeforeAnyNewPublication() throws Exception {
        File files = temp.newFolder(), root = new File(files, "library");
        byte[] first = archive("first"), next = archive("next");
        Revision base = local(files, first), head = revision(next, base.id());
        File stateFile = new File(root, "states/boox-book.json"), captureFile = new File(root, "captures/boox-book.json");
        byte[] oldState = Files.readAllBytes(stateFile.toPath());
        LibraryQueue library = new LibraryQueue(root);
        library.adopt("account", "folder", base.id(), head, next, next, "Book");
        JSONObject state = new JSONObject(new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8));
        JSONObject capture = new JSONObject(new String(Files.readAllBytes(captureFile.toPath()), StandardCharsets.UTF_8));
        JSONObject intent = new JSONObject().put("notebook", "boox-book").put("state", state).put("capture", capture);
        RevisionQueue.write(new File(root, "adopt.json"), intent.toString().getBytes(StandardCharsets.UTF_8));
        RevisionQueue.write(stateFile, oldState);
        LibraryQueue recovered = new LibraryQueue(root);
        assertEquals(0, recovered.stage("account", "folder", "device", new RevisionQueue(new File(files, "recovered-outgoing"))));
        assertTrue(recovered.isApplied("boox-book", head.id()));
    }
}
