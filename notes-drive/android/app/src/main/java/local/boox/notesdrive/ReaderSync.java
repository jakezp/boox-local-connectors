package local.boox.notesdrive;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Durable per-device ancestry; concurrent reading versions never silently overwrite one another. */
final class ReaderSync {
    private final Context context;
    private final String account,folder;
    private final File root,stateFile;
    private final ReaderBundle cache;
    private JSONObject state;
    ReaderSync(Context context,String account,String folder)throws Exception {
        this.context=context;this.account=account;this.folder=folder;
        String namespace=DriveClient.digest("SHA-256",(account+"\n"+folder).getBytes(StandardCharsets.UTF_8));
        root=new File(context.getFilesDir(),"reader/"+namespace);root.mkdirs();
        cache=new ReaderBundle(new File(root,"objects"));stateFile=new File(root,"state.json");
        state=stateFile.exists()?ReaderDriveStore.parse(Files.readAllBytes(stateFile.toPath())):new JSONObject();
        if(!state.has("books"))state.put("books",new JSONObject());
    }
    private void enabled()throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("drive",0);
        DriveClient.require(prefs.getBoolean("readerAutomatic",false)&&account.equals(prefs.getString("account",""))&&
            folder.equals(prefs.getString("folder","")),"Reading sync settings changed");
    }
    private Bundle nativeCall(String method,String id,Bundle args)throws Exception {
        enabled();Bundle response=context.getContentResolver().call(NativeReaderSync.URI,NativeReaderSync.PREFIX+method,id,args);
        DriveClient.require(response!=null&&BuildConfig.HOOK_BUILD.equals(response.getString("build")),"Restart NeoReader to load the current Drive integration");
        DriveClient.require(!response.containsKey("error"),response.getString("error","Native reader operation failed"));return response;
    }
    private String capture(String id)throws Exception {
        Bundle response=nativeCall("Capture",id,null);ParcelFileDescriptor file=response.getParcelable("file");
        DriveClient.require(file!=null,"Native book snapshot missing");
        try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(file)){return cache.capture(id,input);}
    }
    private void save()throws Exception {RevisionQueue.write(stateFile,ReaderBundle.canonical(state));}
    private void adopt(String id,String revision,String manifest)throws Exception {
        state.getJSONObject("books").put(id,new JSONObject().put("base",revision).put("manifest",manifest));save();
    }
    private void publishPending(ReaderDriveStore store)throws Exception {
        if(!state.has("pending"))return;
        Revision pending=Revision.decode(state.getString("pending").getBytes(StandardCharsets.UTF_8));enabled();store.publish(pending);
        adopt(pending.notebook.substring(5),pending.id(),pending.payload);state.remove("pending");save();
    }
    JSONArray review(DriveClient client,String device)throws Exception {
        synchronized(ReaderSync.class) {
            run(client,device); // Publish saved local changes before presenting the heads.
            ReaderDriveStore remote=new ReaderDriveStore(client,folder,cache);remote.load();
            JSONArray result=new JSONArray();
            for(Map.Entry<String,List<String>> book:remote.heads().entrySet()) {
                if(book.getValue().size()<2)continue;
                JSONArray versions=new JSONArray();String title=book.getKey();
                for(String head:book.getValue()) {
                    Revision revision=remote.revisions.get(head);JSONObject record=remote.record(revision.payload);
                    title=record.getString("title");
                    JSONObject content=record.getJSONObject("content");
                    String label=content.getJSONArray("Metadata").getJSONObject(0).optString("progress","No position")+
                        " · "+content.getJSONArray("Bookmark").length()+" bookmarks · "+
                        content.getJSONArray("Annotation").length()+" annotations · Version "+head.substring(0,8);
                    versions.put(new JSONObject().put("id",head).put("label",label));
                }
                result.put(new JSONObject().put("id",book.getKey()).put("title",title).put("versions",versions));
            }
            return result;
        }
    }
    String resolve(DriveClient client,String device,String id,List<String> reviewed,String selected)throws Exception {
        synchronized(ReaderSync.class) {
            run(client,device);
            DriveClient.require(nativeCall("Status",null,null).getBoolean("idle"),"Close NeoReader before choosing a version");
            ReaderDriveStore remote=new ReaderDriveStore(client,folder,cache);remote.load();
            List<String> current=remote.heads().getOrDefault(id,Collections.emptyList());
            DriveClient.require(current.size()>1&&new TreeSet<>(current).equals(new TreeSet<>(reviewed))&&current.contains(selected),
                "Reading versions changed. Review the current versions again.");
            Revision choice=new Revision("book-"+id,device,remote.revisions.get(selected).payload,current);
            remote.download(choice.payload);
            // Keep the local base until native application succeeds. A later local edit
            // will branch from that base and remain a conflict, rather than being lost.
            state.put("resolution",new String(choice.encode(),StandardCharsets.UTF_8));save();
            return runLocked(client,device);
        }
    }
    String run(DriveClient client,String device)throws Exception {
        synchronized(ReaderSync.class) {
            state=stateFile.exists()?ReaderDriveStore.parse(Files.readAllBytes(stateFile.toPath())):new JSONObject();
            if(!state.has("books"))state.put("books",new JSONObject());
            return runLocked(client,device);
        }
    }
    private String runLocked(DriveClient client,String device)throws Exception {
        enabled();Bundle status=nativeCall("Status",null,null);
        if(!status.getBoolean("idle"))return "Waiting for NeoReader to close. Saved versions remain queued.";
        ReaderDriveStore remote=new ReaderDriveStore(client,folder,cache);remote.load();publishPending(remote);
        if(state.has("resolution")) {
            Revision choice=Revision.decode(state.getString("resolution").getBytes(StandardCharsets.UTF_8));
            enabled();remote.publish(choice);state.remove("resolution");save();
        }
        JSONArray nativeBooks=new JSONArray(nativeCall("List",null,null).getString("books"));
        Map<String,String> local=new TreeMap<>();int uploaded=0,downloaded=0,conflicts=0;
        for(int i=0;i<nativeBooks.length();i++) {
            String id=nativeBooks.getJSONObject(i).getString("id");String manifest=capture(id);local.put(id,manifest);
            JSONObject previous=state.getJSONObject("books").optJSONObject(id);
            if(previous!=null&&previous.has("applying")) {
                String candidate=previous.getString("applying");Revision incoming=remote.revisions.get(candidate);
                if(incoming!=null&&(manifest.equals(incoming.payload)||incoming.payload.equals(nativeCall("Receipt",id,null).getString("manifest")))) {
                    adopt(id,candidate,incoming.payload);previous=state.getJSONObject("books").getJSONObject(id);
                }
                else {previous.remove("applying");save();}
            }
            if(previous!=null&&manifest.equals(previous.optString("manifest")))continue;
            List<String> heads=remote.heads().getOrDefault(id,Collections.emptyList());
            if(previous==null&&heads.size()==1&&manifest.equals(remote.revisions.get(heads.get(0)).payload)) {
                adopt(id,heads.get(0),manifest);continue;
            }
            List<String> parents=previous==null||previous.optString("base").isEmpty()?Collections.emptyList():Collections.singletonList(previous.getString("base"));
            for(String parent:parents)DriveClient.require(remote.revisions.containsKey(parent),"Stored reading history is missing from Drive");
            Revision revision=new Revision("book-"+id,device,manifest,parents);
            state.put("pending",new String(revision.encode(),StandardCharsets.UTF_8));save();publishPending(remote);uploaded++;
        }
        List<String> held=new ArrayList<>();
        for(Map.Entry<String,List<String>> book:remote.heads().entrySet()) {
            String id=book.getKey();List<String> heads=book.getValue();
            if(heads.size()!=1){conflicts++;continue;}
            String candidate=heads.get(0);Revision revision=remote.revisions.get(candidate);
            JSONObject previous=state.getJSONObject("books").optJSONObject(id);
            String base=previous==null?"":previous.optString("base"),expected=local.getOrDefault(id,"");
            if(candidate.equals(base))continue;
            if(!remote.descends(candidate,base)){conflicts++;continue;}
            if(previous!=null&&!expected.equals(previous.optString("manifest"))){held.add(id);continue;}
            remote.download(revision.payload);enabled();
            File zip=new File(root,"incoming.zip");try(OutputStream out=new FileOutputStream(zip)){cache.zip(revision.payload,out);}
            JSONObject applying=previous==null?new JSONObject().put("base","").put("manifest",""):previous;
            applying.put("applying",candidate);state.getJSONObject("books").put(id,applying);save();
            try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(zip,ParcelFileDescriptor.MODE_READ_ONLY)) {
                Bundle args=new Bundle();args.putParcelable("file",descriptor);args.putString("manifest",revision.payload);args.putString("expected",expected);
                Bundle result=nativeCall("Apply",id,args);
                DriveClient.require(revision.payload.equals(result.getString("manifest")),"Native book verification failed");
            }
            adopt(id,candidate,revision.payload);downloaded++;
        }
        return "Books stored: "+remote.heads().size()+" · Uploaded: "+uploaded+" · Downloaded: "+downloaded+
            " · Conflicts retained: "+conflicts+(held.isEmpty()?"":" · Local changes waiting: "+held.size());
    }
}
