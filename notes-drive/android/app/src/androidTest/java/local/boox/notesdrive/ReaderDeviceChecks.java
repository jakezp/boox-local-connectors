package local.boox.notesdrive;

import android.app.Instrumentation;
import android.content.Context;
import android.os.*;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import org.json.*;

/** Explicitly invoked live fixture checks; never part of the everyday settings UI. */
public final class ReaderDeviceChecks extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    @Override public void onStart(){new Thread(()->{
        Bundle result=new Bundle();
        try{result.putString("result",runChecks().toString());finish(-1,result);}
        catch(Exception error){result.putString("error",error.toString());finish(0,result);}
    },"reader-device-check").start();}
    private Context context(){return getTargetContext();}
    private Bundle call(String op,String id,Bundle input)throws Exception {
        Bundle result=context().getContentResolver().call(NativeReaderSync.URI,NativeReaderSync.PREFIX+op,id,input);
        DriveClient.require(result!=null&&!result.containsKey("error"),result==null?"No native response":result.getString("error","Native error"));
        DriveClient.require(BuildConfig.HOOK_BUILD.equals(result.getString("build")),"Native source generation differs");return result;
    }
    private String capture(ReaderBundle cache,String id)throws Exception {
        ParcelFileDescriptor file=call("Capture",id,null).getParcelable("file");
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(file)){return cache.capture(id,in);}
    }
    private JSONObject runChecks()throws Exception {
        String operation=args.getString("operation","inspect");
        JSONObject report=new JSONObject().put("operation",operation).put("build",BuildConfig.HOOK_BUILD);
        Bundle status=call("Status",null,null);report.put("idle",status.getBoolean("idle"));
        if(operation.equals("status"))return report;
        DriveClient.require(status.getBoolean("idle"),"Close all Reader tabs first");
        JSONArray list=new JSONArray(call("List",null,null).getString("books"));report.put("books",list.length());
        File root=new File(context().getFilesDir(),"reader-device-validation");root.mkdirs();
        ReaderBundle cache=new ReaderBundle(new File(root,"objects"));
        String id=args.getString("book","");DriveClient.require(Revision.identifier(id),"Pass a native disposable book ID");
        String original=capture(cache,id);JSONObject manifest=cache.manifest(original);JSONObject record=record(cache,manifest);
        report.put("manifest",original).put("id",id).put("files",manifest.getJSONArray("entries").length());
        JSONObject counts=new JSONObject();JSONObject content=record.getJSONObject("content"),notes=record.getJSONObject("notes");
        for(String key:ReaderBundle.keys(content))counts.put(key,content.getJSONArray(key).length());
        for(String key:ReaderBundle.keys(notes))counts.put(key,notes.getJSONArray(key).length());counts.put("readingStatistics",record.optJSONArray("statistics")==null?0:record.getJSONArray("statistics").length());report.put("rows",counts);
        if(operation.equals("inspect"))return report;
        DriveClient.require(record.getString("filename").startsWith("BOOX-Drive-Validation"),"Live writes require an explicitly named disposable validation book");
        if(operation.equals("crash")) {
            String beforeRows=call("Fingerprint",id,null).getString("rows");
            File zip=new File(root,"crash-input.zip");try(OutputStream output=new FileOutputStream(zip)){cache.zip(original,output);}
            Bundle input=new Bundle();input.putString("expected",original);input.putString("manifest",original);input.putString("interruptAt",args.getString("checkpoint","files"));
            boolean interrupted=false;
            try(ParcelFileDescriptor file=ParcelFileDescriptor.open(zip,ParcelFileDescriptor.MODE_READ_ONLY)) {
                input.putParcelable("file",file);call("Apply",id,input);
            } catch(Exception expected) {interrupted=true;}
            DriveClient.require(interrupted,"Native process did not interrupt");
            String after=null;
            for(int attempt=0;attempt<10;attempt++) {try{after=capture(cache,id);break;}catch(Exception retry){Thread.sleep(500);}}
            DriveClient.require(original.equals(after),"Recovered book content differs");
            DriveClient.require(beforeRows.equals(call("Fingerprint",id,null).getString("rows")),"Recovered native rows or identities differ");
            int afterPid=call("Status",null,null).getInt("pid");
            DriveClient.require(status.getInt("pid")!=afterPid,"Expected native process restart");
            report.put("checkpoint",args.getString("checkpoint","files")).put("recovered",true).put("rowsPreserved",true)
                .put("beforePid",status.getInt("pid")).put("afterPid",afterPid);
            return report;
        }
        AuthorizationResult authorization=Tasks.await(Identity.getAuthorizationClient(context()).authorize(AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(DriveSession.SCOPE))).build()),60,TimeUnit.SECONDS);
        DriveClient.require(!authorization.hasResolution()&&authorization.getAccessToken()!=null,"Complete this app's Google sign-in first");
        DriveClient client=new DriveClient(authorization.getAccessToken());
        android.content.SharedPreferences prefs=context().getSharedPreferences("drive",0);
        String account=prefs.getString("account",""),folder=prefs.getString("folder","");
        DriveClient.require(account.equals(client.account().getString("permissionId")),"Google account differs");client.checkFolder(folder);
        ReaderDriveStore remote=new ReaderDriveStore(client,folder,cache);remote.load();
        if(operation.equals("repair-fixture")) {
            int repaired=0;
            for(Map.Entry<String,List<String>> entry:remote.heads().entrySet()) {
                if(entry.getValue().size()!=1)continue;
                Revision head=remote.revisions.get(entry.getValue().get(0));
                if(!head.device.equals("validation-restore")&&!head.device.equals("validation-restore-fixed"))continue;
                remote.download(head.payload);JSONObject fixture=record(cache,cache.manifest(head.payload));
                DriveClient.require(fixture.getString("filename").equals("BOOX-Drive-Validation-Restored.pdf"),"Only the generated restore fixture can be repaired");
                if(head.device.equals("validation-restore"))freshRowIds(fixture);
                sortRecord(fixture);
                String payload=withRecord(cache,head.payload,fixture.getString("id"),fixture);
                remote.publish(new Revision(head.notebook,"validation-restore-fixed",payload,entry.getValue()));repaired++;
            }
            report.put("repaired",repaired).put("sync",new ReaderSync(context(),account,folder).run(client,prefs.getString("device","")));
            return report;
        }
        if(operation.equals("restore-new")) {
            String fresh=UUID.randomUUID().toString().replace("-","");
            record=new JSONObject(record.toString().replace(id,fresh));record.put("id",fresh).put("title","BOOX Drive validation restored")
                .put("filename","BOOX-Drive-Validation-Restored.pdf");
            JSONObject metadata=record.getJSONObject("content").getJSONArray("Metadata").getJSONObject(0);
            metadata.put("title","BOOX Drive validation restored").put("name","BOOX-Drive-Validation-Restored.pdf");
            freshRowIds(record);
            String freshManifest=withRecord(cache,original,fresh,record);Revision revision=new Revision("book-"+fresh,"validation-restore",freshManifest,Collections.emptyList());
            remote.publish(revision);
            report.put("published",revision.id()).put("restoredId",fresh).put("expectedManifest",freshManifest);
            report.put("sync",new ReaderSync(context(),account,folder).run(client,prefs.getString("device","")));
            String restored=capture(cache,fresh);DriveClient.require(freshManifest.equals(restored),"New-device native readback differs");report.put("restoredManifest",restored);
        }else if(operation.equals("roundtrip")||operation.equals("roundtrip-edit")||operation.equals("conflict")) {
            List<String> heads=remote.heads().get(id);DriveClient.require(heads!=null&&heads.size()==1,"Fixture needs one published head");
            String base=heads.get(0);remote.download(remote.revisions.get(base).payload);
            DriveClient.require(original.equals(remote.revisions.get(base).payload),"Native fixture has changes waiting to publish");
            String target=original;
            if(operation.equals("roundtrip-edit")) {
                JSONArray annotations=record.getJSONObject("content").getJSONArray("Annotation");boolean changed=false;
                for(int i=0;i<annotations.length();i++)if(!annotations.getJSONObject(i).optString("note").isEmpty()) {
                    annotations.getJSONObject(i).put("note","Returned through Google Drive");changed=true;break;
                }
                DriveClient.require(changed,"Add a text annotation in NeoReader first");sortRecord(record);
                target=rewriteRecord(cache,original,id,record,false);
            }
            Revision incoming=new Revision("book-"+id,"validation-return",target,heads);remote.publish(incoming);
            if(operation.equals("conflict")) {
                Revision other=new Revision("book-"+id,"validation-concurrent",original,heads);remote.publish(other);
                JSONObject checkpoint=new JSONObject().put("id",id).put("heads",new JSONArray().put(incoming.id()).put(other.id()));
                RevisionQueue.write(new File(root,"conflict.json"),ReaderBundle.canonical(checkpoint));
                report.put("retainedHeads",new JSONArray(remote.heads().get(id)));
            }
            report.put("published",incoming.id()).put("base",base);
            report.put("sync",new ReaderSync(context(),account,folder).run(client,prefs.getString("device","")));
            DriveClient.require(target.equals(capture(cache,id)),"Incoming native readback differs");report.put("nativeReadback",true);
        }else if(operation.equals("verify-conflict")) {
            JSONObject checkpoint=ReaderDriveStore.parse(java.nio.file.Files.readAllBytes(new File(root,"conflict.json").toPath()));
            DriveClient.require(id.equals(checkpoint.getString("id")),"Conflict fixture differs");
            List<String> heads=remote.heads().get(id);DriveClient.require(heads.size()==1,"Conflict still needs selection");
            Revision selected=remote.revisions.get(heads.get(0));JSONArray parents=checkpoint.getJSONArray("heads");
            for(int i=0;i<parents.length();i++)DriveClient.require(selected.parents.contains(parents.getString(i)),"Selected revision did not retain both histories");
            report.put("resolved",true).put("parentsRetained",selected.parents.size());
        }else throw new IOException("Unknown live operation");
        RevisionQueue.write(new File(root,"last-result.json"),ReaderBundle.canonical(report));return report;
    }
    private void freshRowIds(JSONObject record)throws Exception {
        for(String table:new String[]{"Annotation","Bookmark","statistics"}) {
            JSONArray rows=table.equals("statistics")?record.optJSONArray(table):record.getJSONObject("content").getJSONArray(table);
            if(rows!=null)for(int i=0;i<rows.length();i++)rows.getJSONObject(i).put("uuid",UUID.randomUUID().toString().replace("-",""));
        }
        sortRecord(record);
    }
    private void sortRecord(JSONObject record)throws Exception {
        JSONObject metadata=record.getJSONObject("content").getJSONArray("Metadata").getJSONObject(0);
        metadata.remove("idString");metadata.remove("parentId");
        for(String group:new String[]{"content","notes"}) {
            JSONObject tables=record.getJSONObject(group);
            for(String key:ReaderBundle.keys(tables))tables.put(key,ReaderBundle.sortedRows(tables.getJSONArray(key)));
        }
        if(record.has("statistics"))record.put("statistics",ReaderBundle.sortedRows(record.getJSONArray("statistics")));
    }
    private JSONObject record(ReaderBundle cache,JSONObject manifest)throws Exception {
        JSONArray entries=manifest.getJSONArray("entries");for(int i=0;i<entries.length();i++)if(entries.getJSONObject(i).getString("path").equals("record.json"))
            return new JSONObject(new String(cache.entryBytes(entries.getJSONObject(i),ReaderBundle.MAX_RECORD),StandardCharsets.UTF_8));
        throw new IOException("Reader record missing");
    }
    private String withRecord(ReaderBundle cache,String source,String id,JSONObject record)throws Exception {
        return rewriteRecord(cache,source,id,record,true);
    }
    private String rewriteRecord(ReaderBundle cache,String source,String id,JSONObject record,boolean newIdentity)throws Exception {
        File file=new File(context().getCacheDir(),"reader-validation.zip");
        JSONArray entries=cache.manifest(source).getJSONArray("entries");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))) {
            for(int i=0;i<entries.length();i++) {JSONObject entry=entries.getJSONObject(i);String path=entry.getString("path");
                // New-device fixture begins with no handwriting assets whose binary identity would need native conversion.
                DriveClient.require(!newIdentity||path.equals("record.json")||path.equals("book/content"),"Use a fresh fixture before adding handwritten notes");
                zip.putNextEntry(new ZipEntry(path));if(path.equals("record.json"))zip.write(ReaderBundle.canonical(record));else cache.writeEntry(entry,zip);zip.closeEntry();
            }
        }
        return cache.capture(id,new FileInputStream(file));
    }
}
