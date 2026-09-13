package local.boox.notesdrive;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.json.*;

public class ReaderBundleTest {
    private byte[] zip(String id,byte[] book,String extra)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("record.json"));zip.write(ReaderBundle.canonical(new JSONObject().put("schema",1).put("id",id).put("title","Test book")));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("book/content"));zip.write(book);zip.closeEntry();
            if(extra!=null){zip.putNextEntry(new ZipEntry(extra));zip.write(new byte[]{3});zip.closeEntry();}
        }return out.toByteArray();
    }
    private ReaderBundle cache()throws Exception{return new ReaderBundle(Files.createTempDirectory("reader-bundle-").toFile());}
    @Test public void largeBookRoundTripReusesChunks()throws Exception {
        ReaderBundle cache=cache();byte[] book=new byte[ReaderBundle.CHUNK*2+129];new Random(42).nextBytes(book);
        String first=cache.capture("book1",new ByteArrayInputStream(zip("book1",book,null)));
        int count=cache.objects.list().length;
        assertEquals(first,cache.capture("book1",new ByteArrayInputStream(zip("book1",book,null))));assertEquals(count,cache.objects.list().length);
        ByteArrayOutputStream output=new ByteArrayOutputStream();cache.zip(first,output);
        assertEquals(first,cache.capture("book1",new ByteArrayInputStream(output.toByteArray())));
    }
    @Test public void pathTraversalRejected()throws Exception {
        for(String path:Arrays.asList("document/../escape","point//bad","/absolute","book/other","document/./bad","document/a\\b")) {
            try{cache().capture("book1",new ByteArrayInputStream(zip("book1",new byte[]{1},path)));fail(path);}catch(IOException expected){}
        }
    }
    @Test public void crossBookRecordRejected()throws Exception {
        try{cache().capture("book2",new ByteArrayInputStream(zip("book1",new byte[]{1},null)));fail();}catch(IOException expected){}
    }
    @Test public void changedChunkCannotBeRead()throws Exception {
        ReaderBundle cache=cache();String hash=cache.put(new byte[]{1,2,3});Files.write(cache.file(hash).toPath(),new byte[]{4,5,6});
        try{cache.get(hash);fail();}catch(IOException expected){}
    }
    @Test public void manifestRejectsIncorrectChunkCount()throws Exception {
        ReaderBundle cache=cache();String hash=cache.capture("book1",new ByteArrayInputStream(zip("book1",new byte[]{1},null)));
        JSONObject manifest=cache.manifest(hash);manifest.getJSONArray("entries").getJSONObject(0).put("size",ReaderBundle.CHUNK+1);
        String invalid=cache.put(ReaderBundle.canonical(manifest));try{cache.manifest(invalid);fail();}catch(IOException expected){}
    }
    @Test public void canonicalObjectsIgnoreKeyInsertionOrder()throws Exception {
        assertArrayEquals(ReaderBundle.canonical(new JSONObject().put("z",1).put("a","text")),ReaderBundle.canonical(new JSONObject().put("a","text").put("z",1)));
    }
    @Test public void rowOrderIgnoresDatabaseAndJsonColumnOrder()throws Exception {
        JSONObject a=new JSONObject().put("uuid","z").put("event",1);
        JSONObject b=new JSONObject().put("event",2).put("uuid","a");
        JSONArray first=ReaderBundle.sortedRows(new JSONArray().put(a).put(b));
        JSONArray second=ReaderBundle.sortedRows(new JSONArray().put(new JSONObject("{\"uuid\":\"a\",\"event\":2}")).put(new JSONObject("{\"event\":1,\"uuid\":\"z\"}")));
        assertArrayEquals(ReaderBundle.canonical(first),ReaderBundle.canonical(second));
    }
    @Test public void readingForksRemainSeparateHeads()throws Exception {
        ReaderDriveStore store=new ReaderDriveStore(new DriveClient((method,url,type,body)->{throw new IOException("offline");}),"folder",cache());
        String hash=String.join("",Collections.nCopies(64,"a"));
        Revision a=new Revision("book-one","deviceA",hash,Collections.emptyList());
        Revision b=new Revision("book-one","deviceB",hash,Collections.singletonList(a.id()));
        Revision c=new Revision("book-one","deviceC",hash,Collections.singletonList(a.id()));
        store.revisions.put(a.id(),a);store.revisions.put(b.id(),b);store.revisions.put(c.id(),c);
        assertEquals(2,store.heads().get("one").size());assertTrue(store.descends(b.id(),a.id()));assertFalse(store.descends(b.id(),c.id()));
    }
}
