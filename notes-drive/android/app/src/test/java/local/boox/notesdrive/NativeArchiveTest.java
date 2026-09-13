package local.boox.notesdrive;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class NativeArchiveTest {
    private byte[] fixture() throws Exception {
        return Files.readAllBytes(new File("src/main/assets/connection-test.note").toPath());
    }
    private String identity(byte[] bytes) throws Exception {
        for (String path : NativeArchive.unzip(bytes, 64 * 1024 * 1024).keySet())
            if (path.endsWith("/note/pb/note_info")) return path.substring(0, path.indexOf('/'));
        throw new AssertionError("Fixture metadata missing");
    }
    private byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    @Test public void nativeFixtureRetainsEveryPageStrokeAndPointIdentity() throws Exception {
        byte[] bytes = fixture();
        NativeArchive archive = new NativeArchive(bytes, identity(bytes));
        assertEquals(2, archive.pages.size());
        assertTrue(archive.shapes.values().stream().mapToInt(Map::size).sum() >= 8);
        archive.verifyReadback(new NativeArchive(bytes, identity(bytes)));
    }
    @Test public void missingPenPointsAreRejectedBeforeExtraction() throws Exception {
        byte[] bytes = fixture();
        Map<String, byte[]> files = NativeArchive.unzip(bytes, 64 * 1024 * 1024);
        files.keySet().removeIf(path -> path.contains("/point/") && path.endsWith("#points"));
        assertThrows(Exception.class, () -> new NativeArchive(zip(files), identity(bytes)));
    }
    @Test public void nativePointReadbackMustMatchExactRecordedSamples() throws Exception {
        byte[] bytes = fixture();
        Map<String, byte[]> files = NativeArchive.unzip(bytes, 64 * 1024 * 1024);
        String point = files.keySet().stream().filter(path -> path.contains("/point/") &&
            path.endsWith("#points")).findFirst().get();
        byte[] changed = files.get(point).clone(); changed[81] ^= 1; files.put(point, changed);
        NativeArchive before = new NativeArchive(bytes, identity(bytes));
        NativeArchive after = new NativeArchive(zip(files), identity(bytes));
        assertThrows(Exception.class, () -> before.verifyReadback(after));
    }
    @Test public void historicalShapesArePreservedButNotDuplicatedAsActiveShapes() throws Exception {
        byte[] bytes = fixture();
        Map<String, byte[]> files = NativeArchive.unzip(bytes, 64 * 1024 * 1024);
        String shape = files.keySet().stream().filter(path -> path.contains("/shape/") &&
            path.endsWith(".zip")).findFirst().get();
        files.put(identity(bytes) + "/stash/shape/" + shape.substring(shape.lastIndexOf('/') + 1), files.get(shape));
        NativeArchive original = new NativeArchive(bytes, identity(bytes));
        NativeArchive withHistory = new NativeArchive(zip(files), identity(bytes));
        original.verifyReadback(withHistory);
        assertThrows(Exception.class, () -> withHistory.verifyReadback(original));
    }
    @Test public void traversalAtEndOfPathIsRejected() throws Exception {
        byte[] bytes = fixture();
        Map<String, byte[]> files = NativeArchive.unzip(bytes, 64 * 1024 * 1024);
        files.put(identity(bytes) + "/..", new byte[]{1});
        assertThrows(Exception.class, () -> new NativeArchive(zip(files), identity(bytes)));
    }
    @Test public void foreignRootAndMalformedProtobufAreRejected() throws Exception {
        byte[] bytes = fixture();
        assertThrows(Exception.class, () -> new NativeArchive(bytes, "another-book"));
        assertThrows(Exception.class, () -> NativeArchive.protobuf(new byte[]{10, 100, 1}));
        assertThrows(Exception.class, () -> NativeArchive.protobuf(new byte[]{0}));
    }
    @Test public void syntheticMacEditorAddAndEraseKeepNativeIdentityAndTombstones() throws Exception {
        byte[] add = Files.readAllBytes(new File("build/generated/synthetic-fixtures/synthetic-add.note").toPath());
        byte[] erase = Files.readAllBytes(new File("build/generated/synthetic-fixtures/synthetic-erase.note").toPath());
        NativeArchive added = new NativeArchive(add, identity(add)), erased = new NativeArchive(erase, identity(erase));
        assertEquals(added.id, erased.id);
        assertEquals(added.pages, erased.pages);
        assertEquals(6, added.shapes.get(added.pages.get(0)).size());
        assertEquals(6, erased.shapes.get(erased.pages.get(0)).size());
        long removed = 0;
        for (byte[] shape : erased.shapes.get(erased.pages.get(0)).values())
            if (NativeArchive.number(NativeArchive.protobuf(shape), 15, 0) != 0) removed++;
        assertEquals(1, removed);
        erased.verifyReadback(erased);
        assertThrows(Exception.class, () -> erased.verifyReadback(added));
    }
    @Test public void protoDefaultsMatchOmittedFieldsButNonzeroLayerStillDiffers() throws Exception {
        assertEquals(NativeArchive.shapeSignature(new byte[0]), NativeArchive.shapeSignature(new byte[]{48, 0}));
        assertNotEquals(NativeArchive.shapeSignature(new byte[0]), NativeArchive.shapeSignature(new byte[]{48, 1}));
        // Unknown fields must remain significant even when zero.
        assertNotEquals(NativeArchive.shapeSignature(new byte[0]),
            NativeArchive.shapeSignature(new byte[]{(byte) 216, 1, 0}));
    }
    @Test public void syntheticMacStrokeSurvivesProto3ZeroOmission() throws Exception {
        byte[] add = Files.readAllBytes(new File("build/generated/synthetic-fixtures/synthetic-add.note").toPath());
        byte[] readback = Files.readAllBytes(new File("build/generated/synthetic-fixtures/synthetic-normalized.note").toPath());
        new NativeArchive(add, identity(add)).verifyReadback(new NativeArchive(readback, identity(readback)));
    }
    @Test public void lostAttachmentBytesAreRejectedEvenWhenAllStrokesMatch() throws Exception {
        byte[] bytes = fixture();
        Map<String, byte[]> files = NativeArchive.unzip(bytes, 64 * 1024 * 1024);
        String asset = identity(bytes) + "/resource/data/retained-attachment.bin";
        files.put(asset, new byte[]{1, 2, 3});
        NativeArchive before = new NativeArchive(zip(files), identity(bytes));
        files.put(asset, new byte[]{1, 2, 4});
        NativeArchive after = new NativeArchive(zip(files), identity(bytes));
        assertThrows(Exception.class, () -> before.verifyReadback(after));
    }
    private void varint(ByteArrayOutputStream out, int value) {
        while (value >= 128) { out.write((value & 127) | 128); value >>>= 7; }
        out.write(value);
    }
    @Test public void newNotebookAllowsOnlyKnownGeneratedExportMetadata() throws Exception {
        byte[] bytes = fixture();
        String id = identity(bytes), path = id + "/extra/pb/extra";
        Map<String, byte[]> files = NativeArchive.unzip(bytes, 64 * 1024 * 1024);
        files.remove(path);
        NativeArchive before = new NativeArchive(zip(files), id);
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        extra.write(8); varint(extra, 1);
        extra.write(16); varint(extra, 45326);
        extra.write(34); varint(extra, id.length());
        extra.write(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put(path, extra.toByteArray());
        NativeArchive generated = new NativeArchive(zip(files), id);
        before.verifyReadback(generated);
        // An extra unknown field could be content; do not silently approve it.
        extra.write(40); extra.write(1); files.put(path, extra.toByteArray());
        assertThrows(Exception.class, () -> before.verifyReadback(new NativeArchive(zip(files), id)));
        // Existing export metadata is still retained byte-for-byte.
        assertThrows(Exception.class, () -> generated.verifyReadback(new NativeArchive(zip(files), id)));
        files.remove(path); files.put(id + "/extra/unrecognized.bin", new byte[]{1});
        assertThrows(Exception.class, () -> before.verifyReadback(new NativeArchive(zip(files), id)));
    }
    @Test public void parentFolderMismatchIsRejected() throws Exception {
        byte[] bytes = fixture();
        String id = identity(bytes);
        Map<String, byte[]> files = NativeArchive.unzip(bytes, 64 * 1024 * 1024);
        ByteArrayOutputStream list = new ByteArrayOutputStream();
        for (Object value : NativeArchive.protobuf(files.get(id + "/note/pb/note_info")).get(1)) {
            byte[] raw = (byte[]) value;
            Map<Integer, List<Object>> fields = NativeArchive.protobuf(raw);
            if (id.equals(NativeArchive.text(fields, 1))) {
                assertFalse(fields.containsKey(4));
                ByteArrayOutputStream changed = new ByteArrayOutputStream();
                changed.write(raw); changed.write(34);
                byte[] parent = "different-folder".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                varint(changed, parent.length); changed.write(parent); raw = changed.toByteArray();
            }
            list.write(10); varint(list, raw.length); list.write(raw);
        }
        files.put(id + "/note/pb/note_info", list.toByteArray());
        NativeArchive before = new NativeArchive(bytes, id), after = new NativeArchive(zip(files), id);
        assertThrows(Exception.class, () -> before.verifyReadback(after));
    }
}
