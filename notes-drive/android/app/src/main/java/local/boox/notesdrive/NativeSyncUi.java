package local.boox.notesdrive;

import android.content.Context;
import android.content.Intent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Reuses the inspected native sync entry points without impersonating an ONYX account. */
final class NativeSyncUi {
    interface Host extends NativeDriveSettings.Host {
        boolean replacesOnyx();
        boolean automatic();
        String status();
        Context context();
        void sync() throws Exception;
    }
    static void install(ClassLoader loader, Host host) throws Exception {
        NativeAccess api = new NativeAccess(loader);
        NativeDriveSettings.install(loader, host);
        XposedBridge.hookAllMethods(api.type(
            "com.onyx.android.sdk.note.ui.library.viewmodel.LibraryViewModel"),
            "onSyncFolderTree", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!host.replacesOnyx()) return;
                    Context context = ((android.view.View) param.args[0]).getContext();
                    new android.app.AlertDialog.Builder(context).setTitle("Google Drive sync")
                        .setMessage(host.status())
                        .setNegativeButton("Close", null)
                        .setNeutralButton("Settings", (dialog, which) -> openSettings(host))
                        .setPositiveButton("Sync now", (dialog, which) -> {
                            if (!host.automatic()) { openSettings(host); return; }
                            try {
                                host.sync();
                                android.util.Log.i("BooxNotesDrive", "Native library Drive sync requested");
                                android.widget.Toast.makeText(context, "Google Drive sync requested.",
                                    android.widget.Toast.LENGTH_SHORT).show();
                            } catch (Exception error) {
                                android.widget.Toast.makeText(context, "Open Drive settings to retry sync.",
                                    android.widget.Toast.LENGTH_LONG).show();
                            }
                        }).show();
                    param.setResult(null);
                }
            });
        Class<?> viewModel = api.type(
            "com.onyx.android.sdk.note.ui.library.viewmodel.LayoutLibrarySyncViewModel");
        XC_MethodHook presentation = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!host.replacesOnyx() || !(Boolean) api.invoke(param.thisObject, "O")) return;
                field(api, param.thisObject, "showLoginStatus", true);
                field(api, param.thisObject, "loginStatusText", host.status());
                field(api, param.thisObject, "showOnyxAccountManager", true);
                field(api, param.thisObject, "onyxLoginText", "Google Drive settings");
                field(api, param.thisObject, "showOnyxManualSync", true);
                field(api, param.thisObject, "showOnyxSyncSwitch", false);
                field(api, param.thisObject, "onyxSyncEnabled", host.automatic());
                // ONYX progress bars do not describe this connector's journal.
                field(api, param.thisObject, "showSyncView", false);
            }
        };
        XposedBridge.hookAllMethods(viewModel, "updateSyncStatus", presentation);
        XposedBridge.hookAllMethods(viewModel, "updateSyncSwitch", presentation);
        for (String method : new String[]{"jumpOnyxAccount", "onSyncEnabledClick"})
            XposedBridge.hookAllMethods(viewModel, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!host.replacesOnyx()) return;
                    openSettings(host);
                    param.setResult(null);
                }
            });
        XposedBridge.hookAllMethods(viewModel, "sync", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!host.replacesOnyx() || !(Boolean) api.invoke(param.thisObject, "O")) return;
                if (host.automatic()) host.sync(); else openSettings(host);
                param.setResult(null);
            }
        });
        Class<?> helper = api.type("com.onyx.android.sdk.note.ui.service.helper.ServiceActionHelper");
        for (String method : new String[]{"syncNoteContent", "syncNoteTree"})
            XposedBridge.hookAllMethods(helper, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!host.replacesOnyx()) return;
                    if (host.automatic()) host.sync();
                    param.setResult(null);
                }
            });
        XposedBridge.hookAllMethods(api.type("com.onyx.android.sdk.note.ui.common.utils.NoteUtils"),
            "canSyncNote", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (host.replacesOnyx()) param.setResult(host.automatic());
                }
            });
        Class<?> settings = api.type("com.onyx.android.sdk.data.config.system.OnyxSystemConfig");
        XposedBridge.hookAllMethods(settings, "isSyncNoteDataEnabled", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (host.replacesOnyx()) param.setResult(false);
            }
        });
        XposedBridge.hookAllMethods(settings, "setSyncNoteDataEnabled", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (host.replacesOnyx()) param.setResult(null);
            }
        });
        Class<?> forceSync = api.type("com.onyx.android.note.note.action.sync.ForceSyncNoteContentAction");
        java.lang.reflect.Method save = forceSync.getDeclaredMethod("saveNote");
        save.setAccessible(true);
        XposedBridge.hookAllMethods(forceSync, "create", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!host.replacesOnyx()) return;
                android.util.Log.i("BooxNotesDrive", "Routing native editor Sync through Drive");
                if (!host.automatic()) {
                    openSettings(host);
                    param.setResult(api.stat("io.reactivex.Observable", "just", param.thisObject));
                    return;
                }
                // Retain the native save action and its scheduler; replace only the cloud continuation.
                Object saved = save.invoke(param.thisObject);
                Object afterSave = java.lang.reflect.Proxy.newProxyInstance(loader,
                    new Class<?>[]{api.type("io.reactivex.functions.Consumer")}, (proxy, method, args) -> {
                        if (method.getName().equals("accept")) {
                            host.sync();
                            android.util.Log.i("BooxNotesDrive", "Native editor save completed; Drive sync requested");
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                android.widget.Toast.makeText(host.context(), "Saved. Google Drive sync requested.",
                                    android.widget.Toast.LENGTH_SHORT).show());
                            return null;
                        }
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == args[0];
                        return "Drive native save completion";
                    });
                param.setResult(api.invoke(saved, "doOnNext", afterSave));
            }
        });
    }
    private static void field(NativeAccess api, Object model, String field, Object value) throws Exception {
        api.invoke(model.getClass().getField(field).get(model), "set", value);
    }
    static void openSettings(Host host) {
        host.context().startActivity(new Intent().setClassName("local.boox.notesdrive",
            "local.boox.notesdrive.SetupActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
