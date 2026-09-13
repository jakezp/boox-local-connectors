package local.boox.notesdrive;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.*;
import de.robv.android.xposed.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Per-book snapshot/apply adapter for NeoReader 38701. No global database replacement. */
final class NativeReaderSync {
    static final Uri URI=Uri.parse("content://com.onyx.kreader.feature_list.ContentProvider");
    static final String PREFIX="booxDriveReader";
    private static final String PROVIDER="com.onyx.kreader.ui.data.provider.KreaderFeatureListProvider";
    private static final String[] NOTE_TABLES={"ReaderNoteDocumentModel","ReaderNoteResourceModel","ReaderNoteShapeModel"};
    private static final String[] CONTENT_TABLES={"Metadata","Annotation","Bookmark"};
    private static final Set<String> LOCAL_METADATA=new HashSet<>(Arrays.asList("id","idString","parentId","nativeAbsolutePath","nocasePath","location",
        "fetchSource","cloudId","uniqueCloudId","storageId","coverUrl","downloadInfo","fileSyncStatus","userDataSyncStatus"));
    private final Object gate=new Object();
    private final Set<Activity> open=Collections.newSetFromMap(new IdentityHashMap<>());
    private Context context;
    private NativeAccess api;
    private File work;
    private java.nio.channels.FileLock editorLock;
    private RandomAccessFile editorFile;
    private boolean guardsReady;

    static void install(ClassLoader loader) {
        NativeReaderSync host=new NativeReaderSync();host.api=new NativeAccess(loader);
        XposedBridge.hookAllMethods(Application.class,"attach",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam param)throws Throwable {
                Context candidate=(Context)param.args[0];
                if(candidate.getPackageManager().getPackageInfo("com.onyx.kreader",0).getLongVersionCode()!=38701)return;
                host.context=candidate;host.work=new File(candidate.getFilesDir(),"boox-reader-drive");host.work.mkdirs();
                android.util.Log.i("BooxNotesDrive","Native Reader build "+BuildConfig.HOOK_BUILD+" ready for NeoReader 38701");
            }
        });
        XposedBridge.hookAllMethods(ContentProvider.class,"call",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam param)throws Throwable {
                if(host.context==null||!PROVIDER.equals(param.thisObject.getClass().getName())||param.args.length!=3||
                    !(param.args[0] instanceof String)||!((String)param.args[0]).startsWith(PREFIX))return;
                int uid=Binder.getCallingUid();
                int trusted=host.context.getPackageManager().getApplicationInfo("local.boox.notesdrive",0).uid;
                if(uid!=trusted){param.setThrowable(new SecurityException("Reader sync caller is not the Drive companion"));return;}
                long identity=Binder.clearCallingIdentity();
                try {param.setResult(host.call((String)param.args[0],(String)param.args[1],(Bundle)param.args[2]));}
                catch(Exception error){
                    android.util.Log.e("BooxNotesDrive","Reader bridge operation failed: "+param.args[0],error);
                    Bundle result=new Bundle();result.putString("build",BuildConfig.HOOK_BUILD);
                    result.putString("error",error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());param.setResult(result);
                }
                finally{Binder.restoreCallingIdentity(identity);}
            }
        });
        try {
            Class<?> reader = host.api.type("com.onyx.android.sdk.readerview.ui.BaseReaderActivity");
            XposedBridge.hookAllMethods(reader,"onCreate",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam param)throws Throwable {
                    if(host.context==null)return;
                    synchronized(host.gate) {
                        host.acquireEditor();
                        host.open.add((Activity)param.thisObject);
                    }
                }
            });
            XposedBridge.hookAllMethods(reader,"onDestroy",new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam param)throws Throwable {
                    synchronized(host.gate) {
                        host.open.remove((Activity)param.thisObject);
                        if(host.open.isEmpty()&&host.editorLock!=null) {
                            host.editorLock.release();host.editorFile.close();host.editorLock=null;host.editorFile=null;
                            try { host.context.getContentResolver().call(NotesBridge.URI,"readerWake",null,null); }
                            catch(Exception ignored) { /* Persisted network jobs also retry. */ }
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(host.api.type("com.onyx.android.sdk.readerview.WorkBundle"),"ensureCloseDocument",new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam param)throws Throwable {
                    if(param.getThrowable()==null)host.releaseEditor();
                }
            });
            XposedBridge.hookAllMethods(host.api.type("com.onyx.android.sdk.readerview.ReaderBundle"),"setDocumentState",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam param)throws Throwable {
                    String state=String.valueOf(param.args[0]);
                    if(state.equals("OPENING")||state.equals("OPENED"))host.acquireEditor();
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    android.util.Log.i("BooxNotesDrive","Reader document state " + param.args[0]);
                }
            });
            XposedBridge.hookAllMethods(Activity.class,"moveTaskToBack",new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam param)throws Throwable {
                    if(!reader.isInstance(param.thisObject)||!Boolean.TRUE.equals(param.getResult())||host.context==null)return;
                    Activity activity=(Activity)param.thisObject;
                    // Native Back otherwise leaves a deactivated document cached indefinitely.
                    // Use the inspected native close-tab action, including its own final save.
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            if(activity.isFinishing()||activity.isDestroyed())return;
                            Bundle config=host.context.getContentResolver().call(NotesBridge.URI,"readerWake",null,null);
                            Object helper=host.api.invoke(activity,"getReaderBundleHelper");
                            if(helper==null)return;
                            Object activityHelper=host.api.invoke(helper,"getActivityHelper");
                            if(activityHelper==null)return;
                            if(config==null||!config.getBoolean("enabled")||(Boolean)host.api.invoke(activityHelper,"isActivityVisible")||activity.isFinishing())return;
                            Object bundle=host.api.invoke(activity,"getReaderBundle");
                            Object close=host.api.create("com.onyx.android.sdk.readerview.rxaction.CloseDocumentAction",bundle);
                            host.api.invoke(close,"setCheckSync",false);host.api.invoke(close,"setSaveDocumentOptions",true);
                            host.api.invoke(close,"setOpenNextTab",false);host.api.invoke(close,"setFinishActivity",true);
                            host.api.invoke(close,"execute");
                        } catch(Exception error){android.util.Log.e("BooxNotesDrive","Reader close waits for native save",error);}
                    },1000);
                }
            });
            host.guardsReady=true;
        } catch(Exception error) {android.util.Log.e("BooxNotesDrive","Reader editor guards unavailable",error);}
    }
    private void acquireEditor()throws Exception {
        synchronized(gate) {
            if(context==null||editorLock!=null)return;
            editorFile=new RandomAccessFile(new File(work,"editor.lock"),"rw");
            while(true) {
                editorLock=editorFile.getChannel().lock(0,Long.MAX_VALUE,true);
                if(!new File(work,"journal.json").exists())break;
                editorLock.release();editorLock=null;
                try(java.nio.channels.FileLock recovery=editorFile.getChannel().lock()) {recover();}
            }
        }
    }
    private void releaseEditor()throws Exception {
        synchronized(gate) {
            if(editorLock==null)return;
            editorLock.release();editorFile.close();editorLock=null;editorFile=null;
            try { context.getContentResolver().call(NotesBridge.URI,"readerWake",null,null); }
            catch(Exception ignored) { }
        }
    }
    private Bundle call(String method,String id,Bundle args)throws Exception {
        synchronized(gate) {
            try(RandomAccessFile mutex=new RandomAccessFile(new File(work,"editor.lock"),"rw")) {
                java.nio.channels.FileLock acquired;
                try { acquired=mutex.getChannel().tryLock(); }
                catch(java.nio.channels.OverlappingFileLockException held) { acquired=null; }
                if(acquired==null) {
                    Bundle held=new Bundle();held.putString("build",BuildConfig.HOOK_BUILD);held.putBoolean("idle",false);
                    if(!method.equals(PREFIX+"Status"))held.putString("error","Close every NeoReader tab before syncing reading data");
                    return held;
                }
                try(java.nio.channels.FileLock lock=acquired) { return lockedCall(method,id,args); }
            }
        }
    }
    private Bundle lockedCall(String method,String id,Bundle args)throws Exception {
            DriveClient.require(guardsReady,"Reader editor guards are unavailable");
            Bundle out=new Bundle();out.putString("build",BuildConfig.HOOK_BUILD);out.putInt("pid",android.os.Process.myPid());
            if(method.equals(PREFIX+"Status")){out.putBoolean("idle",true);return out;}
            recover();
            if(method.equals(PREFIX+"List")) {
                JSONArray books=new JSONArray();
                try(SQLiteDatabase db=content();Cursor cursor=db.query("Metadata",null,"status=0",null,null,null,"uuid")) {
                    Set<String> ids=new HashSet<>();
                    while(cursor.moveToNext()) {
                        JSONObject row=row(cursor,false);String book=row.optString("uuid"),path=localPath(row);
                        if(Revision.identifier(book)&&new File(path).isFile()&&row.optInt("drmType")<=1&&row.optInt("encryptionType")==0) {
                            DriveClient.require(ids.add(book),"Duplicate native book identity");
                            books.put(new JSONObject().put("id",book).put("title",row.optString("title",row.optString("name"))));
                        }
                    }
                }
                out.putString("books",books.toString());return out;
            }
            DriveClient.require(Revision.identifier(id),"Invalid native reader identity");
            if(method.equals(PREFIX+"Fingerprint")) {
                out.putString("rows",DriveClient.digest("SHA-256",ReaderBundle.canonical(originalRows(id))));return out;
            }
            if(method.equals(PREFIX+"Receipt")) {
                File receipt=new File(work,"applied-"+id+".json");
                if(receipt.exists())out.putString("manifest",ReaderDriveStore.parse(Files.readAllBytes(receipt.toPath())).getString("manifest"));
                return out;
            }
            if(method.equals(PREFIX+"Capture")) {
                File zip=new File(work,"capture-"+id+".zip");capture(id,zip);
                out.putParcelable("file",ParcelFileDescriptor.open(zip,ParcelFileDescriptor.MODE_READ_ONLY));return out;
            }
            if(method.equals(PREFIX+"Apply")) {
                DriveClient.require(args!=null,"Missing reader apply request");
                ParcelFileDescriptor pfd=args.getParcelable("file");DriveClient.require(pfd!=null,"Missing reader bundle");
                File stage=new File(work,"incoming");erase(stage);stage.mkdirs();
                try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pfd)){extract(in,stage);}
                JSONObject record=record(stage);DriveClient.require(id.equals(record.getString("id")),"Incoming reader ID differs");
                String wanted=args.getString("manifest","");
                DriveClient.require(Revision.hash(wanted),"Missing verified reader manifest");
                File incomingZip=new File(work,"incoming.zip");pack(stage,incomingZip);
                ReaderBundle cache=new ReaderBundle(new File(work,"objects"));
                String actual=cache.capture(id,new FileInputStream(incomingZip));
                DriveClient.require(wanted.equals(actual),"Incoming reader bytes differ");
                JSONObject existing=metadata(id);File old=new File(work,"preimage.zip");
                String expected=args.getString("expected","");
                if(existing!=null) {
                    capture(id,old);String current=cache.capture(id,new FileInputStream(old));
                    DriveClient.require(current.equals(expected),"Local reading data changed; keeping both versions");
                } else {
                    DriveClient.require(expected.isEmpty(),"Local reader identity disappeared");
                    DriveClient.require(!notePath(id).exists(),"Orphaned local book notes require review");
                }
                File destination=existing==null?new File(android.os.Environment.getExternalStorageDirectory(),
                    "Books/Google Drive/"+id+"/"+safeName(record.getString("filename"))):new File(localPath(existing));
                DriveClient.require(existing!=null||!destination.exists(),"New book path already exists");
                JSONObject journal=new JSONObject().put("id",id).put("path",destination.getAbsolutePath()).put("existed",existing!=null).put("wanted",wanted).put("phase","prepared");
                if(existing!=null)journal.put("original",originalRows(id));
                RevisionQueue.write(new File(work,"journal.json"),ReaderBundle.canonical(journal));
                try {
                    String interruption=args.getString("interruptAt","");
                    DriveClient.require(interruption.isEmpty()||record.getString("filename").startsWith("BOOX-Drive-Validation"),"Interruption checks require a disposable validation book");
                    apply(stage,destination,interruption);
                    File readback=new File(work,"readback.zip");capture(id,readback);
                    String verified=cache.capture(id,new FileInputStream(readback));
                    DriveClient.require(wanted.equals(verified),"Native reading data readback differs");
                    journal.put("phase","committed");RevisionQueue.write(new File(work,"journal.json"),ReaderBundle.canonical(journal));
                    interrupt("committed",interruption);
                    RevisionQueue.write(new File(work,"applied-"+id+".json"),ReaderBundle.canonical(new JSONObject().put("manifest",wanted)));
                    DriveClient.require(new File(work,"journal.json").delete(),"Cannot finish reader transaction");
                    out.putString("manifest",verified);return out;
                }catch(Exception error){recover();throw error;}
            }
            throw new IOException("Unknown reader operation");
    }
    private SQLiteDatabase content()throws Exception {
        api.stat("com.onyx.android.sdk.reader.dataprovider.ContentSdkDataUtils","openCurrentContentDatabase");
        String name=(String)api.stat("com.raizlabs.android.dbflow.config.FlowManager","getDatabaseName",api.type("com.onyx.android.sdk.data.db.ContentDatabase"));
        return SQLiteDatabase.openDatabase(context.getDatabasePath(name+".db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);
    }
    private SQLiteDatabase statistics()throws Exception {
        Object database=api.stat("com.raizlabs.android.dbflow.config.FlowManager","getDatabase",api.type("com.onyx.android.sdk.data.db.OnyxStatisticsDatabase"));
        api.invoke(database,"getWritableDatabase");
        return SQLiteDatabase.openDatabase(context.getDatabasePath("OnyxStatisticsModel.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);
    }
    private File notePath(String id){return context.getDatabasePath("ReaderNoteDatabase-"+id+".db");}
    private JSONObject metadata(String id)throws Exception {
        try(SQLiteDatabase db=content();Cursor c=db.query("Metadata",null,"uuid=?",new String[]{id},null,null,null)) {
            if(!c.moveToFirst())return null;JSONObject row=row(c,false);DriveClient.require(!c.moveToNext(),"Duplicate native book identity");return row;
        }
    }
    private static String localPath(JSONObject row){String p=row.optString("nativeAbsolutePath");return p.isEmpty()?row.optString("nocasePath"):p;}
    private File document(String id)throws Exception {return new File((String)api.stat("com.onyx.android.sdk.data.sync.KSyncConstant","docDirFilePath",id));}
    private File points(String id)throws Exception {return new File((String)api.stat("com.onyx.android.sdk.sync.ksync.utils.KSyncDirUtils","getDocPointDirPath",id));}
    private void capture(String id,File zip)throws Exception {
        JSONObject data=metadata(id);DriveClient.require(data!=null&&data.optInt("status")==0,"Native book is unavailable");
        DriveClient.require(data.optInt("drmType")<=1&&data.optInt("encryptionType")==0,"Encrypted book requires its original provider");
        File book=new File(localPath(data));DriveClient.require(book.isFile()&&book.length()<=ReaderBundle.MAX_TOTAL,"Book file unavailable or too large");
        File stage=new File(work,"snapshot");erase(stage);stage.mkdirs();
        JSONObject record=new JSONObject().put("schema",1).put("id",id).put("filename",safeName(book.getName()))
            .put("title",data.optString("title",data.optString("name")));
        JSONObject tables=new JSONObject();
        try(SQLiteDatabase db=content()) {
            db.beginTransaction();try {
                for(String table:CONTENT_TABLES)tables.put(table,rows(db,table,table.equals("Metadata")?"uuid=?":"idString=?",id,table.equals("Metadata")));
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
        record.put("content",tables);JSONObject notes=new JSONObject();
        if(notePath(id).exists())try(SQLiteDatabase db=SQLiteDatabase.openDatabase(notePath(id).getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
            for(String table:NOTE_TABLES)notes.put(table,rows(db,table,null,null,false));
        }
        for(String table:NOTE_TABLES)if(!notes.has(table))notes.put(table,new JSONArray());
        record.put("notes",notes);
        JSONArray statistics=new JSONArray();
        try(SQLiteDatabase db=statistics()) {
            statistics=rows(db,"OnyxStatisticsModel","docId=?",id,false);
            for(int i=0;i<statistics.length();i++)statistics.getJSONObject(i).remove("path");
        }
        record.put("statistics",ReaderBundle.sortedRows(statistics));
        RevisionQueue.write(new File(stage,"record.json"),ReaderBundle.canonical(record));
        copyFile(book,new File(stage,"book/content"));copyTree(document(id),new File(stage,"document"));copyTree(points(id),new File(stage,"point"));
        // The per-book metadata must still match after collecting the external assets.
        JSONObject after=metadata(id);for(String key:LOCAL_METADATA) {data.remove(key);if(after!=null)after.remove(key);}
        DriveClient.require(after!=null&&Arrays.equals(ReaderBundle.canonical(data),ReaderBundle.canonical(after)),"Book changed during capture");
        pack(stage,zip);
    }
    private static JSONArray rows(SQLiteDatabase db,String table,String where,String id,boolean metadata)throws Exception {
        List<JSONObject> result=new ArrayList<>();
        try(Cursor c=db.query(table,null,where,id==null?null:new String[]{id},null,null,null)) {
            while(c.moveToNext()){JSONObject row=row(c,metadata);row.remove("id");result.add(row);DriveClient.require(result.size()<=100000,"Reader table too large");}
        }
        return ReaderBundle.sortedRows(new JSONArray(result));
    }
    private static JSONObject row(Cursor c,boolean metadata)throws Exception {
        JSONObject row=new JSONObject();
        for(int i=0;i<c.getColumnCount();i++) {
            String name=c.getColumnName(i);if(metadata&&LOCAL_METADATA.contains(name))continue;
            switch(c.getType(i)) {
                case Cursor.FIELD_TYPE_NULL:row.put(name,JSONObject.NULL);break;
                case Cursor.FIELD_TYPE_INTEGER:row.put(name,c.getLong(i));break;
                case Cursor.FIELD_TYPE_FLOAT:row.put(name,c.getDouble(i));break;
                case Cursor.FIELD_TYPE_BLOB:row.put(name,new JSONObject().put("base64",android.util.Base64.encodeToString(c.getBlob(i),android.util.Base64.NO_WRAP)));break;
                default:row.put(name,c.getString(i));
            }
        }
        return row;
    }
    private static ContentValues values(JSONObject row)throws Exception {
        ContentValues out=new ContentValues();for(String key:ReaderBundle.keys(row)) {
            Object value=row.get(key);
            if(value==JSONObject.NULL)out.putNull(key);
            else if(value instanceof JSONObject)out.put(key,android.util.Base64.decode(((JSONObject)value).getString("base64"),android.util.Base64.NO_WRAP));
            else if(value instanceof Float||value instanceof Double)out.put(key,((Number)value).doubleValue());
            else if(value instanceof Number)out.put(key,((Number)value).longValue());
            else if(value instanceof String)out.put(key,(String)value);
            else throw new IOException("Unsupported reader column value");
        }return out;
    }
    private void apply(File stage,File book)throws Exception { apply(stage,book,""); }
    private static void interrupt(String checkpoint,String requested) {
        if(checkpoint.equals(requested)) {android.os.Process.killProcess(android.os.Process.myPid());throw new IllegalStateException("Process interruption requested");}
    }
    private void apply(File stage,File book,String interruption)throws Exception {
        JSONObject record=record(stage);String id=record.getString("id");JSONObject content=record.getJSONObject("content");
        DriveClient.require(content.getJSONArray("Metadata").length()==1,"Reader metadata missing");
        JSONObject meta=content.getJSONArray("Metadata").getJSONObject(0);
        DriveClient.require(id.equals(meta.getString("uuid"))&&meta.getInt("status")==0,"Invalid reader metadata identity");
        for(String table:new String[]{"Annotation","Bookmark"})for(int i=0;i<content.getJSONArray(table).length();i++)
            DriveClient.require(id.equals(content.getJSONArray(table).getJSONObject(i).getString("idString")),"Cross-book reader record rejected");
        try(SQLiteDatabase db=content()) {
            for(String table:new String[]{"Annotation","Bookmark"})validateIdentities(db,table,"idString",id,content.getJSONArray(table));
        }
        if(record.has("statistics"))try(SQLiteDatabase db=statistics()){validateIdentities(db,"OnyxStatisticsModel","docId",id,record.getJSONArray("statistics"));}
        copyFile(new File(stage,"book/content"),book);
        if(meta.optLong("lastModified")>0)DriveClient.require(book.setLastModified(meta.getLong("lastModified")),"Cannot preserve book modification time");
        replaceTree(new File(stage,"document"),document(id));replaceTree(new File(stage,"point"),points(id));
        interrupt("files",interruption);
        JSONObject notes=record.getJSONObject("notes");
        boolean hasNotes=false;for(String table:NOTE_TABLES)hasNotes|=notes.getJSONArray(table).length()>0;
        if(hasNotes) {
            api.stat("com.onyx.android.sdk.readerview.note.model.ReaderNoteDatabase","openDatabase",id);
            Object definition=api.stat("com.raizlabs.android.dbflow.config.FlowManager","getDatabase",
                api.type("com.onyx.android.sdk.readerview.note.model.ReaderNoteDatabase"));
            // Native openDatabase returns early for its cached name, even if a rollback
            // removed the file. Reopen closes that cached handle and recreates the schema.
            api.invoke(definition,"reopen");
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(notePath(id).getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
                db.beginTransaction();try{for(String table:NOTE_TABLES)replaceRows(db,table,null,null,notes.getJSONArray(table),null);db.setTransactionSuccessful();}finally{db.endTransaction();}
            }
        }else if(notePath(id).exists()) {
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(notePath(id).getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
                db.beginTransaction();try{for(String table:NOTE_TABLES)db.delete(table,null,null);db.setTransactionSuccessful();}finally{db.endTransaction();}
            }
        }
        try(SQLiteDatabase db=content()) {
            ContentValues local=new ContentValues();
            JSONObject existing=metadata(id);
            if(existing!=null) {
                JSONObject preserved=new JSONObject();for(String key:LOCAL_METADATA)if(existing.has(key)&&!key.equals("id"))preserved.put(key,existing.get(key));
                local.putAll(values(preserved));
            } else {local.put("fetchSource",0);local.put("fileSyncStatus",0);local.put("userDataSyncStatus",0);}
            if(existing==null) {
                local.put("idString",book.getPath());local.putNull("parentId");
                local.put("nativeAbsolutePath",book.getPath());local.put("nocasePath",book.getPath());local.put("location",book.getParent());
            }
            db.beginTransaction();try {
                for(String table:CONTENT_TABLES)replaceRows(db,table,table.equals("Metadata")?"uuid=?":"idString=?",id,content.getJSONArray(table),table.equals("Metadata")?local:null);
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
        if(record.has("statistics")) {
            JSONArray statistics=record.getJSONArray("statistics");
            for(int i=0;i<statistics.length();i++)DriveClient.require(id.equals(statistics.getJSONObject(i).getString("docId")),"Cross-book reading statistics rejected");
            try(SQLiteDatabase db=statistics()) {
                ContentValues local=new ContentValues();local.put("path",book.getPath());
                db.beginTransaction();try{replaceRows(db,"OnyxStatisticsModel","docId=?",id,statistics,local);db.setTransactionSuccessful();}finally{db.endTransaction();}
            }
        }
        interrupt("metadata",interruption);
        context.getContentResolver().notifyChange(Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),null);
    }
    private static void validateIdentities(SQLiteDatabase db,String table,String owner,String id,JSONArray rows)throws Exception {
        Set<String> seen=new HashSet<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);String uuid=row.getString("uuid");
            boolean eventHistory=table.equals("OnyxStatisticsModel");
            DriveClient.require(Revision.identifier(uuid)&&(eventHistory||seen.add(uuid))&&id.equals(row.getString(owner)),"Invalid or repeated reading record identity");
            try(Cursor cursor=db.query(table,new String[]{"uuid"},"uuid=? AND "+owner+"<>?",new String[]{uuid,id},null,null,null)) {
                DriveClient.require(!cursor.moveToFirst(),"Reading record identity belongs to another book");
            }
        }
    }
    private static void replaceRows(SQLiteDatabase db,String table,String where,String id,JSONArray rows,ContentValues extra)throws Exception {
        Map<String,ArrayDeque<JSONObject>> originals=new HashMap<>();
        try(Cursor cursor=db.query(table,null,where,id==null?null:new String[]{id},null,null,null)) {
            while(cursor.moveToNext()) {
                JSONObject old=row(cursor,false);
                originals.computeIfAbsent(rowKey(table,old),key->new ArrayDeque<>()).add(old);
            }
        }
        db.delete(table,where,id==null?null:new String[]{id});
        for(int i=0;i<rows.length();i++) {
            JSONObject source=rows.getJSONObject(i);ContentValues row=values(source);row.remove("id");
            ArrayDeque<JSONObject> matches=originals.get(rowKey(table,source));
            JSONObject prior=matches==null?null:matches.poll();
            if(prior!=null&&prior.has("id"))row.put("id",prior.getLong("id"));
            if(extra!=null)row.putAll(extra);
            if(table.equals("OnyxStatisticsModel")&&prior!=null) {
                if(prior.isNull("path"))row.putNull("path");else row.put("path",prior.getString("path"));
            }
            db.insertOrThrow(table,null,row);
        }
    }
    private static String rowKey(String table,JSONObject row)throws Exception {
        if(table.equals("OnyxStatisticsModel")) {
            JSONObject event=new JSONObject(row.toString());event.remove("id");event.remove("path");
            return new String(ReaderBundle.canonical(event),StandardCharsets.UTF_8);
        }
        for(String key:new String[]{"shapeUniqueId","uuid","uniqueId","guid"})if(row.has(key)&&!row.isNull(key)&&!row.getString(key).isEmpty())return key+":"+row.getString(key);
        JSONObject identity=new JSONObject(row.toString());identity.remove("id");return new String(ReaderBundle.canonical(identity),StandardCharsets.UTF_8);
    }
    private void recover()throws Exception {
        if(work==null)return;File journal=new File(work,"journal.json");if(!journal.exists())return;
        JSONObject state=new JSONObject(new String(Files.readAllBytes(journal.toPath()),StandardCharsets.UTF_8));String id=state.getString("id");
        DriveClient.require(Revision.identifier(id),"Invalid reader recovery journal");
        if("committed".equals(state.optString("phase"))) {
            RevisionQueue.write(new File(work,"applied-"+id+".json"),ReaderBundle.canonical(new JSONObject().put("manifest",state.getString("wanted"))));
            DriveClient.require(journal.delete(),"Cannot finish committed reader transaction");return;
        }
        if(state.getBoolean("existed")) {
            File recovery=new File(work,"recovery");erase(recovery);recovery.mkdirs();
            try(InputStream in=new FileInputStream(new File(work,"preimage.zip"))){extract(in,recovery);}
            apply(recovery,new File(state.getString("path")));
            restoreOriginalRows(id,state.getJSONObject("original"));
        }else {
            try(SQLiteDatabase db=content()) {db.beginTransaction();try{for(String table:CONTENT_TABLES)db.delete(table,table.equals("Metadata")?"uuid=?":"idString=?",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();}}
            try(SQLiteDatabase db=statistics()){db.delete("OnyxStatisticsModel","docId=?",new String[]{id});}
            erase(new File(state.getString("path")));erase(document(id));erase(points(id));
            // A newly created per-book database contains no preexisting user data.
            deleteNoteDatabase(id);
        }
        DriveClient.require(journal.delete(),"Reader recovery could not finish");
    }
    private JSONObject originalRows(String id)throws Exception {
        JSONObject original=new JSONObject(), tables=new JSONObject(), notes=new JSONObject();
        try(SQLiteDatabase db=content()) {
            for(String table:CONTENT_TABLES)tables.put(table,rawRows(db,table,table.equals("Metadata")?"uuid=?":"idString=?",id));
        }
        if(notePath(id).exists())try(SQLiteDatabase db=SQLiteDatabase.openDatabase(notePath(id).getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
            for(String table:NOTE_TABLES)notes.put(table,rawRows(db,table,null,null));
        }
        try(SQLiteDatabase db=statistics()) { original.put("statistics",rawRows(db,"OnyxStatisticsModel","docId=?",id)); }
        return original.put("content",tables).put("notes",notes);
    }
    private static JSONArray rawRows(SQLiteDatabase db,String table,String where,String id)throws Exception {
        JSONArray result=new JSONArray();try(Cursor c=db.query(table,null,where,id==null?null:new String[]{id},null,null,null)) {
            while(c.moveToNext())result.put(row(c,false));
        }return result;
    }
    private void restoreOriginalRows(String id,JSONObject original)throws Exception {
        try(SQLiteDatabase db=content()) {
            db.beginTransaction();try {
                for(String table:CONTENT_TABLES) {
                    db.delete(table,table.equals("Metadata")?"uuid=?":"idString=?",new String[]{id});
                    JSONArray rows=original.getJSONObject("content").getJSONArray(table);
                    for(int i=0;i<rows.length();i++)db.insertOrThrow(table,null,values(rows.getJSONObject(i)));
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
        try(SQLiteDatabase db=statistics()) {
            db.beginTransaction();try {
                db.delete("OnyxStatisticsModel","docId=?",new String[]{id});
                JSONArray rows=original.getJSONArray("statistics");
                for(int i=0;i<rows.length();i++)db.insertOrThrow("OnyxStatisticsModel",null,values(rows.getJSONObject(i)));
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
        JSONObject notes=original.getJSONObject("notes");
        if(notes.length()==0)deleteNoteDatabase(id);
        else try(SQLiteDatabase db=SQLiteDatabase.openDatabase(notePath(id).getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            db.beginTransaction();try {
                for(String table:NOTE_TABLES) {
                    db.delete(table,null,null);JSONArray rows=notes.getJSONArray(table);
                    for(int i=0;i<rows.length();i++)db.insertOrThrow(table,null,values(rows.getJSONObject(i)));
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
    }
    private void deleteNoteDatabase(String id)throws Exception {
        Class<?> type=api.type("com.onyx.android.sdk.readerview.note.model.ReaderNoteDatabase");
        String current=(String)api.stat("com.raizlabs.android.dbflow.config.FlowManager","getDatabaseName",type);
        if(current.equals("ReaderNoteDatabase-"+id)) {
            Object definition=api.stat("com.raizlabs.android.dbflow.config.FlowManager","getDatabase",type);
            api.invoke(definition,"destroy");
        } else {
            context.deleteDatabase(notePath(id).getName());
        }
    }
    private static JSONObject record(File stage)throws Exception {
        File file=new File(stage,"record.json");DriveClient.require(file.length()<=ReaderBundle.MAX_RECORD,"Reader record exceeds limit");
        JSONObject record=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        DriveClient.require(record.getInt("schema")==1&&Revision.identifier(record.getString("id")),"Invalid reader record");return record;
    }
    private static String safeName(String name)throws Exception {
        DriveClient.require(!name.isEmpty()&&name.length()<=240&&!name.contains("/")&&!name.contains("\\")&&!name.equals(".")&&!name.equals("..")&&!name.contains("\u0000"),"Unsafe book filename");return name;
    }
    private static void copyFile(File source,File target)throws Exception {
        DriveClient.require(source.isFile()&&!Files.isSymbolicLink(source.toPath()),"Missing or linked reader asset");
        long length=source.length(),modified=source.lastModified();target.getParentFile().mkdirs();File tmp=new File(target.getPath()+".drive-tmp");
        try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(tmp)) {
            byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);out.getFD().sync();
        }
        DriveClient.require(source.length()==length&&source.lastModified()==modified&&tmp.length()==length,"Reader asset changed during capture");
        Files.move(tmp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    private static void copyTree(File source,File target)throws Exception {
        if(!source.exists())return;DriveClient.require(!Files.isSymbolicLink(source.toPath()),"Linked reader directory rejected");
        if(source.isFile()){copyFile(source,target);return;}
        File[] files=source.listFiles();DriveClient.require(files!=null,"Cannot list reader assets");
        for(File file:files)copyTree(file,new File(target,file.getName()));
    }
    private static void replaceTree(File source,File target)throws Exception {erase(target);copyTree(source,target);}
    private static void erase(File file)throws IOException {
        if(!file.exists())return;if(Files.isSymbolicLink(file.toPath()))throw new IOException("Linked reader path rejected");
        if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("Cannot list reader staging");for(File child:children)erase(child);}
        if(!file.delete())throw new IOException("Cannot remove reader staging");
    }
    private static void extract(InputStream input,File target)throws Exception {
        Set<String> seen=new HashSet<>();long total=0;
        try(ZipInputStream zip=new ZipInputStream(input)){ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String path=entry.getName();ReaderBundle.path(path);DriveClient.require(seen.add(path)&&seen.size()<=ReaderBundle.MAX_FILES&&!entry.isDirectory(),"Invalid reader archive entries");
                File out=new File(target,path);out.getParentFile().mkdirs();
                try(FileOutputStream stream=new FileOutputStream(out)){byte[] buffer=new byte[65536];int n;
                    while((n=zip.read(buffer))!=-1){total+=n;DriveClient.require(total<=ReaderBundle.MAX_TOTAL,"Reader archive too large");stream.write(buffer,0,n);}stream.getFD().sync();}
                zip.closeEntry();
            }
        }
        DriveClient.require(seen.contains("record.json")&&seen.contains("book/content"),"Incomplete reader archive");
    }
    private static void pack(File root,File zip)throws Exception {
        try(FileOutputStream file=new FileOutputStream(zip);ZipOutputStream out=new ZipOutputStream(file)) {
            packTree(root,root,out);out.finish();file.getFD().sync();
        }
    }
    private static void packTree(File root,File dir,ZipOutputStream zip)throws Exception {
        File[] files=dir.listFiles();DriveClient.require(files!=null,"Cannot enumerate reader snapshot");Arrays.sort(files,Comparator.comparing(File::getName));
        for(File file:files)if(file.isDirectory())packTree(root,file,zip);else {
            String path=root.toPath().relativize(file.toPath()).toString();ReaderBundle.path(path);ZipEntry entry=new ZipEntry(path);entry.setTime(0);zip.putNextEntry(entry);
            try(InputStream in=new FileInputStream(file)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)zip.write(bytes,0,n);}zip.closeEntry();
        }
    }
}
