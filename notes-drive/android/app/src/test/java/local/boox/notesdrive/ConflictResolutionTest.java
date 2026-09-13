package local.boox.notesdrive;

import static org.junit.Assert.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;

public class ConflictResolutionTest {
    private final byte[] payload = new FolderRecord("one", null, "Folder").encode();
    private final String hash = DriveClient.digest("SHA-256", payload);
    public ConflictResolutionTest() throws Exception {}

    private RevisionCatalog catalog(Revision... revisions) throws Exception {
        Map<String, byte[]> records = new HashMap<>();
        for (Revision revision : revisions) records.put(revision.id(), revision.encode());
        return new RevisionCatalog(records, Collections.singletonMap(hash, payload));
    }
    private JSONObject branch(Revision revision) throws Exception {
        return new JSONObject().put("revision", new String(revision.encode(), StandardCharsets.UTF_8));
    }
    @Test public void choiceRetainsAllHeadsAndCanFastForwardBothDevices() throws Exception {
        Revision a = new Revision("folder-one", "a", hash, Collections.emptyList());
        Revision b = new Revision("folder-one", "b", null, Collections.emptyList());
        RevisionCatalog before = catalog(a, b);
        Revision chosen = ConflictResolution.prepare(before, "folder-one", before.heads("folder-one"),
            a.id(), "resolver", branch(b));
        RevisionCatalog after = catalog(a, b, chosen);
        assertEquals(hash, chosen.payload);
        assertEquals(Collections.singletonList(chosen.id()), after.heads("folder-one"));
        assertTrue(NativeInbox.descendant(after, chosen.id(), a.id()));
        assertTrue(NativeInbox.descendant(after, chosen.id(), b.id()));
    }
    @Test public void deletedChoiceRetainsHistoryForRestore() throws Exception {
        Revision a = new Revision("folder-one", "a", hash, Collections.emptyList());
        Revision b = new Revision("folder-one", "b", null, Collections.emptyList());
        RevisionCatalog before = catalog(a, b);
        Revision chosen = ConflictResolution.prepare(before, "folder-one", before.heads("folder-one"),
            b.id(), "resolver", branch(a));
        assertNull(chosen.payload);
        assertEquals(3, catalog(a, b, chosen).revisions.size());
    }
    @Test public void changedHeadsAndUnpublishedLocalBranchAreRejected() throws Exception {
        Revision a = new Revision("folder-one", "a", hash, Collections.emptyList());
        Revision b = new Revision("folder-one", "b", null, Collections.emptyList());
        Revision c = new Revision("folder-one", "c", hash, Collections.singletonList(b.id()));
        RevisionCatalog before = catalog(a, b), after = catalog(a, b, c);
        assertThrows(Exception.class, () -> ConflictResolution.prepare(after, "folder-one",
            before.heads("folder-one"), a.id(), "resolver", branch(a)));
        assertThrows(Exception.class, () -> ConflictResolution.prepare(before, "folder-one",
            before.heads("folder-one"), a.id(), "resolver", branch(c)));
        assertThrows(Exception.class, () -> ConflictResolution.prepare(before, "folder-one",
            before.heads("folder-one"), c.id(), "resolver", null));
    }
}
