package local.boox.notesdrive;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FolderSyncTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void folderEncodingIsCanonicalAndKeepsUnicodeTitles() throws Exception {
        FolderRecord folder = new FolderRecord("folder-id", null, "Ideas — α");
        assertEquals("Ideas — α", FolderRecord.decode(folder.encode()).title);
        assertEquals("{\"id\":\"folder-id\",\"kind\":\"folder\",\"parent\":null,\"schema\":1,\"title\":\"Ideas — α\"}",
            new String(folder.encode(), StandardCharsets.UTF_8));
    }
    @Test public void selfParentAndAmbiguousRecordsAreRejected() throws Exception {
        assertThrows(Exception.class, () -> new FolderRecord("same", "same", "Title"));
        byte[] bytes = new FolderRecord("id", null, "Title").encode();
        String duplicate = new String(bytes, StandardCharsets.UTF_8).replace("\"schema\":1", "\"schema\":1,\"schema\":1");
        assertThrows(Exception.class, () -> FolderRecord.decode(duplicate.getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void emptyFolderRenameAndMoveHaveTheirOwnAncestry() throws Exception {
        LibraryQueue library = new LibraryQueue(temp.newFolder());
        RevisionQueue queue = new RevisionQueue(temp.newFolder());
        FolderRecord first = new FolderRecord("id", null, "First"), renamed = new FolderRecord("id", "parent", "Second");
        assertTrue(library.captureRecord("account", "directory", "folder-id", first.title, first.encode()));
        assertEquals(1, library.stage("account", "directory", "device", queue));
        assertTrue(library.captureRecord("account", "directory", "folder-id", renamed.title, renamed.encode()));
        assertEquals(1, library.stage("account", "directory", "device", queue));
        List<Revision> published = new ArrayList<>();
        assertEquals(2, queue.retry("account", "directory", (revision, bytes) -> published.add(revision)));
        assertEquals(Collections.singletonList(published.get(0).id()), published.get(1).parents);
    }
    @Test public void tombstoneSurvivesRestartAndRestoreDescendsFromIt() throws Exception {
        File libraryRoot = temp.newFolder(), outgoingRoot = temp.newFolder();
        LibraryQueue library = new LibraryQueue(libraryRoot);
        RevisionQueue queue = new RevisionQueue(outgoingRoot);
        FolderRecord folder = new FolderRecord("id", null, "Folder");
        library.captureRecord("account", "directory", "folder-id", folder.title, folder.encode());
        library.stage("account", "directory", "device", queue);
        library.captureRecord("account", "directory", "folder-id", folder.title, null);
        library.stage("account", "directory", "device", queue);
        List<Revision> published = new ArrayList<>();
        new RevisionQueue(outgoingRoot).retry("account", "directory", (revision, bytes) -> {
            if (revision.payload == null) assertNull(bytes);
            published.add(revision);
        });
        Revision deleted = published.get(1);
        assertNull(deleted.payload);
        library = new LibraryQueue(libraryRoot);
        library.captureRecord("account", "directory", "folder-id", folder.title, folder.encode());
        library.stage("account", "directory", "device", queue);
        queue.retry("account", "directory", (revision, bytes) ->
            assertEquals(Collections.singletonList(deleted.id()), revision.parents));
    }
    @Test public void deletedCaptureKeepsItsTitleAndCanRepairAnOldBlankReceipt() throws Exception {
        File root = temp.newFolder();
        LibraryQueue library = new LibraryQueue(root);
        library.captureRecord("account", "directory", "folder-id", "Folder", null);
        assertFalse(library.captureRecord("account", "directory", "folder-id", "", null));
        File capture = new File(root, "captures/folder-id.json");
        assertEquals("Folder", new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(capture.toPath()),
            StandardCharsets.UTF_8)).getString("title"));
        library.captureRecord("account", "directory", "folder-other", "", null);
        assertFalse(library.captureRecord("account", "directory", "folder-other", "Recovered title", null));
        capture = new File(root, "captures/folder-other.json");
        assertEquals("Recovered title", new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(capture.toPath()),
            StandardCharsets.UTF_8)).getString("title"));
    }
    @Test public void metadataAdoptionAndDuplicateAcknowledgmentDoNotRepublish() throws Exception {
        File root = temp.newFolder();
        LibraryQueue library = new LibraryQueue(root);
        FolderRecord folder = new FolderRecord("id", null, "Folder");
        Revision first = new Revision("folder-id", "mac", DriveClient.digest("SHA-256", folder.encode()), Collections.emptyList());
        library.adopt("account", "directory", "", first, folder.encode(), folder.encode(), "Folder");
        Revision removed = new Revision("folder-id", "mac", null, Collections.singletonList(first.id()));
        library.adopt("account", "directory", first.id(), removed, null, null, "Folder");
        library.adopt("account", "directory", first.id(), removed, null, null, "Folder");
        assertTrue(library.isApplied("folder-id", removed.id()));
        assertEquals(0, library.stage("account", "directory", "android", new RevisionQueue(temp.newFolder())));
    }
}
