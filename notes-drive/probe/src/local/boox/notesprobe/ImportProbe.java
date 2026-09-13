package local.boox.notesprobe;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.Map;
import java.util.UUID;

/** Research probe: only repairs B2 -> D fixture imports on the inspected APK. */
public final class ImportProbe implements IXposedHookLoadPackage {
    private static final String NOTES = "com.onyx.android.note";
    private static final String SOURCE_ID = "c8b6607d-2615-4a6a-a124-ed76c09a961e";
    private static final String DESTINATION_TITLE = "GDrive-Sync-Probe-D";
    private static final String TAG = "BooxNotesProbe";
    private volatile boolean supported;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam info) throws Throwable {
        if (!NOTES.equals(info.packageName)) return;
        XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.args[0];
                supported = context.getPackageManager().getPackageInfo(NOTES, 0)
                    .getLongVersionCode() == 45326;
                Log.i(TAG, supported ? "Fixture probe ready" : "Unsupported Notes version; inactive");
            }
        });
        Class<?> noteModelData = Class.forName(
            "com.onyx.android.sdk.scribble.data.note.NoteModelData", false, info.classLoader);
        XposedBridge.hookAllMethods(noteModelData, "copyNoteAttr", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!supported || param.getResult() == null) return;
                Object context = param.args[0];
                if (!DESTINATION_TITLE.equals(call(context, "getNoteTitle")) ||
                    !SOURCE_ID.equals(call(context, "getSrcDocId"))) return;
                String destination = (String) call(context, "getDstDocId");
                if (destination == null || SOURCE_ID.equals(destination)) return;
                UUID.fromString(destination);
                Object result = call(context, "getNoteDocIdMap");
                if (!(result instanceof Map)) return;
                @SuppressWarnings("unchecked")
                Map<String, String> mapping = (Map<String, String>) result;
                String existing = mapping.get(SOURCE_ID);
                if (existing != null && !destination.equals(existing)) {
                    Log.e(TAG, "Conflicting mapping; fixture repair skipped");
                    return;
                }
                if (existing == null) {
                    mapping.put(SOURCE_ID, destination);
                    Log.i(TAG, "Added fixture notebook mapping");
                }
            }
        });
    }

    private static Object call(Object object, String method) throws ReflectiveOperationException {
        return object.getClass().getMethod(method).invoke(object);
    }
}
