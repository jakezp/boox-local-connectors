package local.boox.notesdrive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Streaming book bundles. Large books reuse independently verified 3 MiB chunks. */
final class ReaderBundle {
    static final int CHUNK = 3 * 1024 * 1024;
    static final long MAX_TOTAL = 1024L * 1024 * 1024;
    static final int MAX_FILES = 20000;
    static final int MAX_RECORD = 16 * 1024 * 1024;
    final File objects;
    ReaderBundle(File objects) throws IOException {
        this.objects = objects;
        DriveClient.require(objects.isDirectory() || objects.mkdirs(), "Cannot create reader object store");
    }
    static byte[] canonical(Object value) throws Exception {
        return json(value).getBytes(StandardCharsets.UTF_8);
    }
    static JSONArray sortedRows(JSONArray rows) throws Exception {
        List<String> encoded=new ArrayList<>();
        for(int i=0;i<rows.length();i++)encoded.add(json(rows.getJSONObject(i)));
        Collections.sort(encoded);
        JSONArray result=new JSONArray();
        for(String value:encoded)result.put(new JSONObject(value));
        return result;
    }
    static List<String> keys(JSONObject object) { List<String> keys=new ArrayList<>(); object.keys().forEachRemaining(keys::add); return keys; }
    private static String json(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            List<String> keys = keys(object); Collections.sort(keys);
            List<String> parts = new ArrayList<>();
            for(String key:keys) parts.add(JSONObject.quote(key)+":"+json(object.get(key)));
            return "{"+String.join(",",parts)+"}";
        }
        if(value instanceof JSONArray) {
            List<String> parts = new ArrayList<>(); JSONArray array=(JSONArray)value;
            for(int i=0;i<array.length();i++) parts.add(json(array.get(i)));
            return "["+String.join(",",parts)+"]";
        }
        String encoded=new JSONArray().put(value).toString(); return encoded.substring(1,encoded.length()-1);
    }
    String put(byte[] bytes) throws Exception {
        String hash = DriveClient.digest("SHA-256",bytes); File target=file(hash);
        if(!target.exists()) RevisionQueue.write(target,bytes);
        else DriveClient.require(Arrays.equals(bytes, get(hash)),"Reader cache collision");
        return hash;
    }
    File file(String hash) throws Exception {
        DriveClient.require(Revision.hash(hash),"Invalid reader object hash"); return new File(objects,hash);
    }
    byte[] get(String hash) throws Exception {
        byte[] bytes=DriveClient.read(new FileInputStream(file(hash)));
        DriveClient.require(hash.equals(DriveClient.digest("SHA-256",bytes)),"Reader cache checksum mismatch");return bytes;
    }
    static void path(String path) throws Exception {
        DriveClient.require(path != null && path.length()<=1024 && !path.contains("\\") && !path.contains("\u0000") &&
            (path.equals("record.json") || path.equals("book/content") || path.startsWith("document/") || path.startsWith("point/")),
            "Unsupported reader bundle entry");
        for(String part:path.split("/",-1)) DriveClient.require(!part.isEmpty() && !part.equals(".") && !part.equals(".."),"Unsafe reader entry path");
    }
    String capture(String id, InputStream input) throws Exception {
        DriveClient.require(id.matches("[A-Za-z0-9_-]{1,128}"),"Invalid book identity");
        TreeMap<String,JSONObject> entries=new TreeMap<>(); long total=0;
        try(ZipInputStream zip=new ZipInputStream(input)) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName(); path(name);
                DriveClient.require(!entry.isDirectory()&&!entries.containsKey(name)&&entries.size()<MAX_FILES,"Duplicate or excessive reader entries");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");JSONArray chunks=new JSONArray();long size=0;
                byte[] buffer=new byte[CHUNK]; int n;
                while((n=fill(zip,buffer))>0) {
                    size+=n;total+=n;DriveClient.require(total<=MAX_TOTAL,"Reader bundle exceeds 1 GiB");
                    byte[] chunk=Arrays.copyOf(buffer,n);digest.update(chunk);chunks.put(put(chunk));
                }
                entries.put(name,new JSONObject().put("path",name).put("size",size).put("sha256",hex(digest.digest())).put("chunks",chunks));
                zip.closeEntry();
            }
        }
        DriveClient.require(entries.containsKey("record.json")&&entries.containsKey("book/content"),"Book or reading record missing");
        byte[] record=entryBytes(entries.get("record.json"),MAX_RECORD);
        JSONObject data=new JSONObject(new String(record,StandardCharsets.UTF_8));
        DriveClient.require(id.equals(data.getString("id"))&&data.getInt("schema")==1,"Reader record identity mismatch");
        JSONObject manifest=new JSONObject().put("schema",1).put("id",id).put("title",data.getString("title"))
            .put("entries",new JSONArray(entries.values()));
        byte[] encoded=canonical(manifest);DriveClient.require(encoded.length<=DriveClient.MAX_BYTES,"Reader manifest too large");
        return put(encoded);
    }
    JSONObject manifest(String hash) throws Exception {
        JSONObject data=new JSONObject(new String(get(hash),StandardCharsets.UTF_8));
        DriveClient.require(data.getInt("schema")==1&&Revision.identifier(data.getString("id")),"Invalid reader manifest");
        JSONArray entries=data.getJSONArray("entries");Set<String> paths=new HashSet<>();long total=0;
        DriveClient.require(entries.length()<=MAX_FILES,"Excessive reader file count");
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);String name=entry.getString("path");path(name);
            long size=entry.getLong("size");total+=size;
            DriveClient.require(paths.add(name)&&size>=0&&size<=MAX_TOTAL&&total<=MAX_TOTAL&&Revision.hash(entry.getString("sha256")),"Invalid reader file");
            JSONArray chunks=entry.getJSONArray("chunks");
            DriveClient.require(chunks.length()==(size+CHUNK-1)/CHUNK,"Invalid reader chunk count");
            for(int j=0;j<chunks.length();j++)DriveClient.require(Revision.hash(chunks.getString(j)),"Invalid reader chunk hash");
        }
        DriveClient.require(paths.contains("record.json")&&paths.contains("book/content"),"Incomplete book manifest");return data;
    }
    byte[] entryBytes(JSONObject entry,int limit) throws Exception {
        DriveClient.require(entry.getLong("size")<=limit,"Reader record too large");
        ByteArrayOutputStream out=new ByteArrayOutputStream();writeEntry(entry,out);return out.toByteArray();
    }
    void writeEntry(JSONObject entry,OutputStream out) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;JSONArray chunks=entry.getJSONArray("chunks");
        for(int i=0;i<chunks.length();i++) {
            byte[] bytes=get(chunks.getString(i));
            DriveClient.require(bytes.length>0&&bytes.length<=CHUNK&&(i==chunks.length()-1||bytes.length==CHUNK),"Invalid reader chunk size");
            digest.update(bytes);out.write(bytes);size+=bytes.length;
        }
        DriveClient.require(size==entry.getLong("size")&&hex(digest.digest()).equals(entry.getString("sha256")),"Reader file checksum mismatch");
    }
    void zip(String hash,OutputStream output) throws Exception {
        JSONArray entries=manifest(hash).getJSONArray("entries");
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            for(int i=0;i<entries.length();i++) {
                JSONObject entry=entries.getJSONObject(i);ZipEntry ze=new ZipEntry(entry.getString("path"));ze.setTime(0);
                zip.putNextEntry(ze);writeEntry(entry,zip);zip.closeEntry();
            }
        }
    }
    static int fill(InputStream input,byte[] bytes)throws IOException {
        int offset=0,n;while(offset<bytes.length&&(n=input.read(bytes,offset,bytes.length-offset))!=-1)offset+=n;return offset;
    }
    static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
}
