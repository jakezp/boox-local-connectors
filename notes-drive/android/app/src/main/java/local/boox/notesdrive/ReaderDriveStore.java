package local.boox.notesdrive;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Reader namespace shares the existing app-owned grant and managed Drive directory. */
final class ReaderDriveStore {
    private static final String MARKER="booxReaderProtocol";
    private final DriveClient client;
    private final String folder;
    private final ReaderBundle cache;
    final Map<String,Revision> revisions=new TreeMap<>();
    private final Map<String,JSONObject> objects=new HashMap<>();
    ReaderDriveStore(DriveClient client,String folder,ReaderBundle cache)throws Exception {
        this.client=client;this.folder=DriveClient.fileId(folder);this.cache=cache;
    }
    private String metadata(JSONObject file)throws Exception {
        DriveClient.fileId(file.getString("id"));
        JSONObject props=file.getJSONObject("appProperties");JSONArray parents=file.getJSONArray("parents");
        String hash=props.getString("sha256"),kind=props.getString("kind");
        DriveClient.require(!file.optBoolean("trashed")&&parents.length()==1&&folder.equals(parents.getString(0))&&
            "1".equals(props.optString(MARKER))&&Revision.hash(hash)&&
            (kind.equals("chunk")||kind.equals("manifest")||kind.equals("revision"))&&
            file.getLong("size")>0&&file.getLong("size")<=DriveClient.MAX_BYTES,"Invalid reader Drive object");
        return kind+":"+hash;
    }
    void load()throws Exception {
        client.checkFolder(folder);Set<String> pages=new HashSet<>();String page="";
        do {
            DriveClient.require(pages.add(page)&&pages.size()<=200,"Incomplete reader listing");
            String query="'"+folder+"' in parents and trashed=false and appProperties has { key='"+MARKER+"' and value='1' }";
            JSONObject response=parse(client.request("GET",DriveClient.API+"files?spaces=drive&pageSize=1000&q="+DriveClient.encode(query)+
                "&fields=nextPageToken,incompleteSearch,files(id,parents,size,md5Checksum,trashed,appProperties)"+
                (page.isEmpty()?"":"&pageToken="+DriveClient.encode(page)),null,null));
            DriveClient.require(!response.optBoolean("incompleteSearch"),"Incomplete reader search");
            JSONArray files=response.getJSONArray("files");
            for(int i=0;i<files.length();i++) {
                JSONObject file=files.getJSONObject(i);String key=metadata(file),hash=key.substring(key.indexOf(':')+1);
                JSONObject old=objects.putIfAbsent(key,file);
                if(old!=null)DriveClient.require(old.getLong("size")==file.getLong("size")&&old.getString("md5Checksum").equals(file.getString("md5Checksum")),"Reader duplicate object mismatch");
                if(key.startsWith("revision:")) {
                    Revision revision=Revision.decode(fetch("revision",hash));
                    DriveClient.require(revision.notebook.startsWith("book-")&&revision.payload!=null,"Unsupported reader revision");
                    revisions.put(hash,revision);
                }
                DriveClient.require(objects.size()<=100000&&revisions.size()<=10000,"Reader history limit reached");
            }
            page=response.optString("nextPageToken");
        }while(!page.isEmpty());
        for(Map.Entry<String,Revision> entry:revisions.entrySet())for(String parent:entry.getValue().parents)
            DriveClient.require(revisions.containsKey(parent)&&revisions.get(parent).notebook.equals(entry.getValue().notebook),"Reader history is incomplete");
        // Iterative topological validation also handles long offline histories.
        Map<String,Integer> remaining=new HashMap<>();
        Map<String,List<String>> children=new HashMap<>();
        ArrayDeque<String> ready=new ArrayDeque<>();
        for(Map.Entry<String,Revision> entry:revisions.entrySet()) {
            remaining.put(entry.getKey(),entry.getValue().parents.size());
            if(entry.getValue().parents.isEmpty())ready.add(entry.getKey());
            for(String parent:entry.getValue().parents)
                children.computeIfAbsent(parent,key->new ArrayList<>()).add(entry.getKey());
        }
        int visited=0;
        while(!ready.isEmpty()) {
            String id=ready.remove();visited++;
            for(String child:children.getOrDefault(id,Collections.emptyList())) {
                int count=remaining.get(child)-1;remaining.put(child,count);
                if(count==0)ready.add(child);
            }
        }
        DriveClient.require(visited==revisions.size(),"Invalid reader revision graph");
    }
    boolean descends(String candidate,String base) {
        if(base.isEmpty())return true;
        ArrayDeque<String> todo=new ArrayDeque<>();Set<String> visited=new HashSet<>();todo.add(candidate);
        while(!todo.isEmpty()) {String id=todo.remove();if(id.equals(base))return true;
            if(visited.add(id)&&revisions.containsKey(id))todo.addAll(revisions.get(id).parents);}
        return false;
    }
    Map<String,List<String>> heads() {
        Map<String,List<String>> result=new TreeMap<>();Set<String> parents=new HashSet<>();
        for(Revision r:revisions.values())parents.addAll(r.parents);
        for(Map.Entry<String,Revision> e:revisions.entrySet())if(!parents.contains(e.getKey()))
            result.computeIfAbsent(e.getValue().notebook.substring(5),key->new ArrayList<>()).add(e.getKey());
        return result;
    }
    byte[] fetch(String kind,String hash)throws Exception {
        JSONObject metadata=objects.get(kind+":"+hash);
        DriveClient.require(metadata!=null,"Reader Drive object is missing");
        byte[] bytes=cache.file(hash).exists()?cache.get(hash):client.request("GET",DriveClient.API+"files/"+
            DriveClient.fileId(metadata.getString("id"))+"?alt=media",null,null);
        DriveClient.require(bytes.length==metadata.getLong("size")&&hash.equals(DriveClient.digest("SHA-256",bytes))&&
            metadata.getString("md5Checksum").equals(DriveClient.digest("MD5",bytes)),"Reader Drive checksum mismatch");
        cache.put(bytes);return bytes;
    }
    void download(String manifest)throws Exception {
        fetch("manifest",manifest);JSONArray entries=cache.manifest(manifest).getJSONArray("entries");
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);JSONArray chunks=entry.getJSONArray("chunks");
            for(int j=0;j<chunks.length();j++)fetch("chunk",chunks.getString(j));
            cache.writeEntry(entry,new java.io.OutputStream(){public void write(int b){}public void write(byte[] b,int off,int len){}});
        }
    }
    JSONObject record(String manifest)throws Exception {
        fetch("manifest",manifest);
        JSONArray entries=cache.manifest(manifest).getJSONArray("entries");
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);
            if(!entry.getString("path").equals("record.json"))continue;
            JSONArray chunks=entry.getJSONArray("chunks");
            for(int j=0;j<chunks.length();j++)fetch("chunk",chunks.getString(j));
            return parse(cache.entryBytes(entry,ReaderBundle.MAX_RECORD));
        }
        throw new java.io.IOException("Reading record missing");
    }
    void publish(Revision revision)throws Exception {
        JSONArray entries=cache.manifest(revision.payload).getJSONArray("entries");
        for(int i=0;i<entries.length();i++) {
            JSONArray chunks=entries.getJSONObject(i).getJSONArray("chunks");
            for(int j=0;j<chunks.length();j++)upload("chunk",chunks.getString(j),cache.get(chunks.getString(j)));
        }
        upload("manifest",revision.payload,cache.get(revision.payload));
        upload("revision",revision.id(),revision.encode());revisions.put(revision.id(),revision);
    }
    private void upload(String kind,String hash,byte[] bytes)throws Exception {
        if(objects.containsKey(kind+":"+hash)){fetch(kind,hash);return;}
        String boundary="boox_reader_"+UUID.randomUUID();
        JSONObject info=new JSONObject().put("name","reader-"+kind+"-"+hash).put("parents",new JSONArray().put(folder))
            .put("appProperties",new JSONObject().put(MARKER,"1").put("sha256",hash).put("kind",kind));
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"+info+
            "\r\n--"+boundary+"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        JSONObject uploaded=parse(client.request("POST","https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,parents,size,md5Checksum,trashed,appProperties",
            "multipart/related; boundary="+boundary,body.toByteArray()));
        DriveClient.require((kind+":"+hash).equals(metadata(uploaded)),"Reader upload identity mismatch");
        objects.put(kind+":"+hash,uploaded);fetch(kind,hash);
    }
    static JSONObject parse(byte[] bytes)throws Exception{return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
}
