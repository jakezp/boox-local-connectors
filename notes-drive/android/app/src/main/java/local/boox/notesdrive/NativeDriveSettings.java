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
        install(loader, host, false);
    }

    static void installLibrary(ClassLoader loader, Host host) throws Exception {
        install(loader, host, true);
    }

    private static void install(ClassLoader loader, Host host, boolean reader) throws Exception {
        String headerAction = reader ? "BOOX_READER_DRIVE_TITLE" : HEADER;
        String switchAction = reader ? "BOOX_READER_DRIVE_SYNC" : SWITCH;
        String fragmentName = reader ? "com.onyx.common.library.ui.LibrarySettingsFragment"
            : "com.onyx.android.sdk.note.ui.setting.ui.NoteSettingsFragment";
        NativeAccess api = new NativeAccess(loader);
        Class<?> load = api.type(reader ? "com.onyx.common.library.action.LoadLibrarySettingsDataAction"
            : "com.onyx.android.sdk.note.ui.setting.action.LoadSettingsDataAction");
        String appendCloud = reader ? "i" : "l";
        // Inspected Notes 45326: l(List) appends the ONYX header and its switch.
        load.getDeclaredMethod(appendCloud, List.class);
        XposedBridge.hookAllMethods(load, appendCloud, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.getThrowable() != null || host.context() == null) return;
                @SuppressWarnings("unchecked") List<Object> rows = (List<Object>) param.args[0];
                for (Object row : rows)
                    if (headerAction.equals(api.invoke(row, "getIntentAction"))) return;
                Object heading = api.create(DATA);
                api.invoke(heading, "setIntentAction", headerAction);
                api.invoke(heading, "setTitle", "Google Drive Sync");
                api.invoke(heading, "setViewType", 5);
                Object toggle = api.create(DATA);
                api.invoke(toggle, "setIntentAction", switchAction);
                api.invoke(toggle, "setTitle", "Sync Switch");
                api.invoke(toggle, "setCheckBoxStyle", 1);
                api.invoke(toggle, "setViewType", 4);
                api.invoke(toggle, "setTitleRightIcon",
                    api.type("com.onyx.android.sdk.note.ui.R$drawable").getField("ic_note_tips").getInt(null));
                api.invoke(toggle, "setShowDivider", false);
                Bundle state = host.syncSettings(null);
                android.util.Log.i("BooxNotesDrive", headerAction+" loaded enabled="+state.getBoolean("enabled")+" error="+state.getString("error",""));
                update(api, toggle, state);
                rows.add(heading);
                rows.add(toggle);
            }
        });
        Class<?> adapter = api.type(fragmentName + (reader ? "$initView$3" : "$initView$2"));
        for (String method : new String[]{"onItemClick", "onRightIconContainerClick", "onTitleRightIconClick"}) {
            final boolean info = method.equals("onTitleRightIconClick");
            Class<?> methodOwner = adapter;
            while (true) {
                try { methodOwner.getDeclaredMethod(method, View.class, int.class); break; }
                catch (NoSuchMethodException missing) { methodOwner = methodOwner.getSuperclass(); if (methodOwner == null) throw missing; }
            }
            XposedBridge.hookAllMethods(methodOwner, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object row = api.invoke(param.thisObject, "getItem", param.args[1]);
                    if (row == null || !switchAction.equals(api.invoke(row, "getIntentAction"))) return;
                    param.setResult(null);
                    if (info) {
                        openSettings(host);
                        return;
                    }
                    View view = (View) param.args[0];
                    if (view == null) return;
                    Object targetAdapter = param.thisObject;
                    int position = (Integer) param.args[1];
                    api.invoke(row, "setEnable", false);
                    worker.execute(() -> {
                        try {
                            // Read persisted state; native checkbox gestures can update the
                            // displayed model before the adapter callback runs.
                            boolean enabled = !host.syncSettings(null).getBoolean("enabled");
                            Bundle state = host.syncSettings(enabled);
                            android.util.Log.i("BooxNotesDrive", switchAction+" requested="+enabled+" enabled="+state.getBoolean("enabled")+" error="+state.getString("error",""));
                            view.post(() -> {
                                try {
                                    update(api, row, state);
                                    api.invoke(targetAdapter, "notifyItemChanged", position);
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
        Class<?> fragment = api.type(fragmentName);
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
