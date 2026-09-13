package local.boox.notesapply;

import android.app.Application;
import android.content.Context;
import android.util.AtomicFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/** Capture actual native ID mappings; only two named, disposable imports. */
public final class ImportMaps implements IXposedHookLoadPackage {
    private final Map<String, Object> pending = new ConcurrentHashMap<>();
    private Context app;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam info) throws Throwable {
        if (!"com.onyx.android.note".equals(info.packageName)) return;
        XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.args[0];
                if (context.getPackageManager().getPackageInfo(info.packageName, 0)
                        .getLongVersionCode() == 45326) app = context;
            }
        });
        Class<?> model = Class.forName(
            "com.onyx.android.sdk.scribble.data.note.NoteModelData", false, info.classLoader);
        XposedBridge.hookAllMethods(model, "copyNoteAttr", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (app == null || param.getResult() == null) return;
                Object context = param.args[0];
                String title = (String) call(context, "getNoteTitle");
                if (!"GDrive-Sync-Probe-Target".equals(title) &&
                    !"GDrive-Sync-Probe-Staged".equals(title)) return;
                String source = (String) call(context, "getSrcDocId");
                if (!"c8b6607d-2615-4a6a-a124-ed76c09a961e".equals(source)) return;
                String destination = (String) call(context, "getDstDocId");
                @SuppressWarnings("unchecked")
                Map<String, String> mapping = (Map<String, String>) call(context, "getNoteDocIdMap");
                mapping.put(source, destination);
                pending.put(destination, context);
            }
        });
        Class<?> provider = Class.forName(
            "com.onyx.android.sdk.scribble.provider.LocalNoteProvider", false, info.classLoader);
        XposedBridge.hookAllMethods(provider, "saveNoteList", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (app == null || param.args.length == 0 || !(param.args[0] instanceof Iterable)) return;
                for (Object note : (Iterable<?>) param.args[0]) {
                    String destination = (String) call(note, "getUniqueId");
                    Object context = pending.get(destination);
                    if (context == null) continue;
                    Map<?, ?> shapes = (Map<?, ?>) call(context, "getShapeIdCopyMap");
                    if (shapes.isEmpty()) continue;
                    JSONObject record = new JSONObject()
                        .put("source", call(context, "getSrcDocId"))
                        .put("destination", destination)
                        .put("title", call(note, "getTitle"))
                        .put("pages", new JSONObject((Map<?, ?>) call(context, "getPageIdCopyMap")))
                        .put("shapes", new JSONObject(shapes));
                    File directory = new File(app.getFilesDir(), "boox-apply-probe-maps");
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Map directory");
                    AtomicFile file = new AtomicFile(new File(directory,
                        call(context, "getNoteTitle") + ".json"));
                    FileOutputStream output = file.startWrite();
                    try {
                        output.write(record.toString().getBytes(StandardCharsets.UTF_8));
                        file.finishWrite(output);
                    } catch (Throwable error) {
                        file.failWrite(output);
                        throw error;
                    }
                    pending.remove(destination);
                    android.util.Log.i("BooxApplyProbe", "Saved fixture ID mapping");
                }
            }
        });
    }

    private static Object call(Object object, String method) throws ReflectiveOperationException {
        return object.getClass().getMethod(method).invoke(object);
    }
}
