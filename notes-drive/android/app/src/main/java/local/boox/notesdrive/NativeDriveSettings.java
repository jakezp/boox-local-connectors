package local.boox.notesdrive;

import android.os.Bundle;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Adds native settings models; the vendor adapter supplies layout, switch and info icon. */
final class NativeDriveSettings {
    interface Host {
        android.content.Context context();
        Bundle syncSettings(Boolean enabled) throws Exception;
    }
    private static final String HEADER = "BOOX_DRIVE_TITLE";
    private static final String SWITCH = "BOOX_DRIVE_SYNC";
    private static final String DATA = "com.onyx.android.sdk.kui.data.UniversalSettingsData";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    static void install(ClassLoader loader, Host host) throws Exception {
        NativeAccess api = new NativeAccess(loader);
        Class<?> load = api.type("com.onyx.android.sdk.note.ui.setting.action.LoadSettingsDataAction");
        // Inspected Notes 45326: l(List) appends the ONYX header and its switch.
        load.getDeclaredMethod("l", List.class);
        XposedBridge.hookAllMethods(load, "l", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.getThrowable() != null || host.context() == null) return;
                @SuppressWarnings("unchecked") List<Object> rows = (List<Object>) param.args[0];
                for (Object row : rows)
                    if (HEADER.equals(api.invoke(row, "getIntentAction"))) return;
                Object heading = api.create(DATA);
                api.invoke(heading, "setIntentAction", HEADER);
                api.invoke(heading, "setTitle", "Google Drive Sync");
                api.invoke(heading, "setViewType", 5);
                Object toggle = api.create(DATA);
                api.invoke(toggle, "setIntentAction", SWITCH);
                api.invoke(toggle, "setTitle", "Sync Switch");
                api.invoke(toggle, "setCheckBoxStyle", 1);
                api.invoke(toggle, "setViewType", 4);
                api.invoke(toggle, "setTitleRightIcon",
                    api.type("com.onyx.android.sdk.note.ui.R$drawable").getField("ic_note_tips").getInt(null));
                api.invoke(toggle, "setShowDivider", false);
                update(api, toggle, host.syncSettings(null));
                rows.add(heading);
                rows.add(toggle);
            }
        });
        Class<?> adapter = api.type(
            "com.onyx.android.sdk.note.ui.setting.ui.NoteSettingsFragment$initView$2");
        for (String method : new String[]{"onItemClick", "onRightIconContainerClick", "onTitleRightIconClick"}) {
            final boolean info = method.equals("onTitleRightIconClick");
            adapter.getDeclaredMethod(method, View.class, int.class);
            XposedBridge.hookAllMethods(adapter, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object row = api.invoke(param.thisObject, "getItem", param.args[1]);
                    if (row == null || !SWITCH.equals(api.invoke(row, "getIntentAction"))) return;
                    param.setResult(null);
                    if (info) {
                        openSettings(host);
                        return;
                    }
                    View view = (View) param.args[0];
                    if (view == null) return;
                    boolean enabled = !(Boolean) api.invoke(row, "isChecked");
                    api.invoke(row, "setEnable", false);
                    worker.execute(() -> {
                        try {
                            Bundle state = host.syncSettings(enabled);
                            view.post(() -> {
                                try {
                                    update(api, row, state);
                                    if (state.getBoolean("setupRequired")) openSettings(host);
                                    else if (state.containsKey("error"))
                                        android.widget.Toast.makeText(view.getContext(), state.getString("error"),
                                            android.widget.Toast.LENGTH_LONG).show();
                                } catch (Exception error) { report(error); }
                            });
                        } catch (Exception error) {
                            view.post(() -> {
                                try { api.invoke(row, "setEnable", true); }
                                catch (Exception failed) { report(failed); }
                                openSettings(host);
                            });
                            report(error);
                        }
                    });
                }
            });
        }
        Class<?> fragment = api.type("com.onyx.android.sdk.note.ui.setting.ui.NoteSettingsFragment");
        java.lang.reflect.Method reload = fragment.getDeclaredMethod("loadData");
        reload.setAccessible(true);
        XposedBridge.hookAllMethods(fragment, "onSupportVisible", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                // Returning from setup must reflect the persisted state, even if ONYX account count is unchanged.
                reload.invoke(param.thisObject);
            }
        });
    }

    private static void update(NativeAccess api, Object row, Bundle state) throws Exception {
        api.invoke(row, "setChecked", state.getBoolean("enabled"));
        api.invoke(row, "setEnable", true);
    }

    static void openSettings(Host host) {
        host.context().startActivity(new android.content.Intent().setClassName("local.boox.notesdrive",
            "local.boox.notesdrive.SetupActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static void report(Exception error) {
        android.util.Log.e("BooxNotesDrive", "Native Drive settings unavailable", error);
    }
}
