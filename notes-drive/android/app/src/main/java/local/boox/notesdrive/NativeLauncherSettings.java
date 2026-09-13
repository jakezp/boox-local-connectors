package local.boox.notesdrive;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Launcher-hosted Notes Settings only: no native document or ONYX account hooks. */
final class NativeLauncherSettings {
    static void install(ClassLoader loader) {
        XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Context context = (Context) param.args[0];
                    if (context.getPackageManager().getPackageInfo("com.onyx", 0).getLongVersionCode() != 56737 ||
                        context.getPackageManager().getPackageInfo("com.onyx.android.note", 0).getLongVersionCode() != 45326)
                        return;
                    NativeDriveSettings.install(loader, new NativeDriveSettings.Host() {
                        public Context context() { return context; }
                        public Bundle syncSettings(Boolean enabled) throws Exception {
                            Bundle request = new Bundle();
                            if (enabled != null) request.putBoolean("enabled", enabled);
                            Bundle response = context.getContentResolver().call(NotesBridge.URI,
                                "syncSettings", null, request);
                            if (response == null) throw new java.io.IOException("Drive setup is unavailable.");
                            return response;
                        }
                    });
                    NativeDriveSettings.installLibrary(loader, new NativeDriveSettings.Host() {
                        public Context context() { return context; }
                        public Bundle syncSettings(Boolean enabled) throws Exception {
                            Bundle request = new Bundle();
                            if (enabled != null) request.putBoolean("enabled", enabled);
                            Bundle response = context.getContentResolver().call(NotesBridge.URI,
                                "readerSettings", null, request);
                            if (response == null) throw new java.io.IOException("Drive setup is unavailable.");
                            return response;
                        }
                    });
                    android.util.Log.i("BooxNotesDrive", "Launcher Notes Settings build " +
                        BuildConfig.HOOK_BUILD + " ready for launcher 56737");
                } catch (Exception error) {
                    android.util.Log.e("BooxNotesDrive", "Launcher Notes Settings hook unavailable", error);
                }
            }
        });
    }
}
