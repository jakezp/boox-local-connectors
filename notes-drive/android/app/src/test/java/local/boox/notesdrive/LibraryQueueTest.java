package local.boox.notesdrive;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LibraryQueueTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String ID = "native-notebook";

    private byte[] archive(String content, long time, String path) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry(path);
            entry.setTime(time);
            zip.putNextEntry(entry);
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    private byte[] archive(String content) throws Exception {
        return archive(content, 1700000000000L, ID + "/note/pb/note_info");
    }

    @Test public void fingerprintIgnoresZipTimeButPreservesEveryContentByte() throws Exception {
        byte[] first = archive("one");
        byte[] later = archive("one", 1800000000000L, ID + "/note/pb/note_info");
        assertFalse(java.util.Arrays.equals(first, later));
        assertEquals(NativeSnapshot.fingerprint(first, ID), NativeSnapshot.fingerprint(later, ID));
        assertNotEquals(NativeSnapshot.fingerprint(first, ID), NativeSnapshot.fingerprint(archive("two"), ID));
    }

    @Test public void rejectsForeignIdentityTraversalAndMissingMetadata() throws Exception {
        for (String path : new String[] {"other/note/pb/note_info", ID + "/../note/pb/note_info", ID + "/point/data"})
            assertThrows(Exception.class, () -> NativeSnapshot.fingerprint(archive("one", 1700000000000L, path), ID));
    }

    @Test public void capturesCoalesceAndKeepLocalAncestryAcrossRestart() throws Exception {
        File root = temp.newFolder(), outgoing = temp.newFolder();
        LibraryQueue library = new LibraryQueue(root);
        RevisionQueue queue = new RevisionQueue(outgoing);
        assertTrue(library.capture("account", "folder", ID, "Title", archive("one")));
        assertFalse(library.capture("account", "folder", ID, "Title", archive("one")));
        assertEquals(1, library.stage("account", "folder", "device", queue));
        library.capture("account", "folder", ID, "Title", archive("two"));
        library.capture("account", "folder", ID, "Title", archive("three"));
        library = new LibraryQueue(root);
        assertEquals(1, library.stage("account", "folder", "device", queue));
        assertEquals(0, library.stage("account", "folder", "device", queue));
        List<Revision> published = new ArrayList<>();
        assertEquals(2, queue.retry("account", "folder", (revision, payload) -> published.add(revision)));
        assertEquals(Collections.singletonList(published.get(0).id()), published.get(1).parents);
        assertEquals(DriveClient.digest("SHA-256", archive("three")), published.get(1).payload);
    }

    @Test public void crashAfterBranchAssignmentRecoversTheIdenticalRevision() throws Exception {
        File root = temp.newFolder(), outgoing = temp.newFolder();
        LibraryQueue library = new LibraryQueue(root);
        RevisionQueue queue = new RevisionQueue(outgoing);
        library.capture("account", "folder", ID, "Title", archive("one"));
        library.stage("account", "folder", "device", queue);
        File stateFile = new File(root, "states/boox-" + ID + ".json");
        JSONObject state = new JSONObject(new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8));
        state.put("enqueued", false);
        Files.write(stateFile.toPath(), state.toString().getBytes(StandardCharsets.UTF_8));
        for (File job : new File(outgoing, "jobs").listFiles()) Files.delete(job.toPath());
        assertEquals(0, new LibraryQueue(root).stage("account", "folder", "device", new RevisionQueue(outgoing)));
        queue.retry("account", "folder", (revision, payload) ->
            assertEquals(state.getString("revision"), new String(revision.encode(), StandardCharsets.UTF_8)));
    }

    @Test public void differentDestinationCannotRedirectCapturedNotebooks() throws Exception {
        LibraryQueue library = new LibraryQueue(temp.newFolder());
        RevisionQueue queue = new RevisionQueue(temp.newFolder());
        library.capture("account", "folder", ID, "Title", archive("one"));
        assertThrows(Exception.class, () -> library.capture("other", "folder", ID, "Title", archive("two")));
        assertThrows(Exception.class, () -> library.stage("account", "other", "device", queue));
        assertEquals(0, queue.pending());
    }

    @Test public void completedUploadsDoNotFillTheJournalAfterSixtyFourSaves() throws Exception {
        RevisionQueue queue = new RevisionQueue(temp.newFolder());
        Revision previous = null;
        for (int i = 0; i < 90; i++) {
            byte[] bytes = archive("edit-" + i);
            Revision next = new Revision("book", "device", DriveClient.digest("SHA-256", bytes),
                previous == null ? Collections.emptyList() : Collections.singletonList(previous.id()));
            queue.enqueue("account", "folder", next, bytes);
            assertEquals(1, queue.retry("account", "folder", (revision, payload) -> {}));
            previous = next;
        }
        assertEquals(0, queue.pending());
    }
}
