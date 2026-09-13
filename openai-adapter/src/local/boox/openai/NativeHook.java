package local.boox.openai;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.json.*;

public final class NativeHook implements IXposedHookLoadPackage {
    private static final String SERVICE = "com.onyx.android.sdk.aiassistant.client.service.";
    private static final Uri BRIDGE = Uri.parse("content://local.boox.openai.bridge");
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final String sessionId = UUID.randomUUID().toString();
    private final AtomicReference<Object> pendingChat = new AtomicReference<>();
    private ClassLoader loader;
    private Context context;
    private boolean reader;
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam packageInfo) throws Throwable {
        if (!"com.onyx.aiassistant".equals(packageInfo.packageName) && !"com.onyx.kreader".equals(packageInfo.packageName)) return;
        reader = "com.onyx.kreader".equals(packageInfo.packageName);
        loader = packageInfo.classLoader;
        DisclaimerViews.install();
        XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { context = (Context) param.args[0]; }
        });
        XposedBridge.hookAllMethods(type(SERVICE + "AIAssistantRemoteServiceConnection"), "asInterface", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object original = param.getResult();
                if (original == null || Proxy.isProxyClass(original.getClass())) return;
                Class<?> contract = type(SERVICE + "IAIAssistantService");
                param.setResult(Proxy.newProxyInstance(loader, new Class<?>[]{contract}, (proxy, method, args) -> {
                    String name = method.getName();
                    if ("asBinder".equals(name)) return method.invoke(original, args);
                    if ("toString".equals(name)) return "BOOX OpenAI adapter";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == args[0];
                    handle(name, args == null ? new Object[0] : args);
                    return null;
                }));
                android.util.Log.i("BooxOpenAIHook", "Native assistant bridge attached: " + packageInfo.packageName);
            }
        });
        Class<?> viewModel = type("com.onyx.android.sdk.aiassistant.client.ui.viewmodel.ConversationViewModel");
        XposedBridge.hookAllMethods(viewModel, "updateSubTitle", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                String label = "OpenAI connector", subtitle = label;
                try {
                    JSONObject status = new JSONObject(invokeProvider("connectionStatus", "{}"));
                    label = status.getString("label"); subtitle = status.getString("subtitle");
                } catch (Exception ignored) {}
                call(call(param.thisObject, "getSubTitle"), "set", subtitle);
                call(call(param.thisObject, "getSecondaryTips"), "set", label);
            }
        });
        XposedBridge.hookAllMethods(viewModel, "onAssistantErrorEvent", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object event = param.args[0];
                if (!Integer.valueOf(-2).equals(call(event, "getErrorCode"))) return;
                String message = (String) call(event, "getErrorMsg");
                if (message != null && !message.isEmpty()) {
                    // BOOX otherwise replaces all adapter errors with "Generation stopped".
                    call(call(param.thisObject, "getConversationAdapter"), "onAIReplyFinish", message);
                }
            }
        });
        Class<?> adapter = type("com.onyx.android.sdk.aiassistant.client.ui.adapter.ConversationAdapter");
        XposedBridge.hookAllMethods(adapter, "addData", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length != 1 || !(param.args[0] instanceof List)) return;
                Set<String> ids = new HashSet<>();
                for (Object record : (List<?>) call(param.thisObject, "getConversationList")) {
                    String id = (String) call(record, "getUniqueId");
                    if (id != null && !id.isEmpty()) ids.add(id);
                }
                List<Object> unique = new ArrayList<>();
                for (Object record : (List<?>) param.args[0]) {
                    String id = (String) call(record, "getUniqueId");
                    if (id == null || id.isEmpty() || ids.add(id)) unique.add(record);
                }
                // Opening a reader conversation can trigger two overlapping initial history loads.
                param.args[0] = unique;
            }
        });
    }
    private void handle(String method, Object[] args) {
        if ("abort".equals(method) || "cancelAnalyze".equals(method) || "interruptConversation".equals(method)) {
            int boundary = generation.incrementAndGet();
            Object stoppedCallback = pendingChat.getAndSet(null);
            try {
                invokeProvider("abort", new JSONObject().put("sessionId", sessionId).put("generation", boundary).toString());
            } catch (Exception ignored) {}
            if ("interruptConversation".equals(method) && stoppedCallback != null) {
                // The row's Stop action waits for a final chat event to restore the input box.
                try {
                    call(stoppedCallback, "read", staticCall(type(SERVICE + "AIAssistantOutputArgs"), "success", "Stopped.", 0));
                } catch (Exception ignored) {}
            }
        }
        if (args.length == 0) return;
        Object callback = args[args.length - 1];
        if (callback == null) return;
        boolean output = implementsType(callback.getClass(), SERVICE + "IAIAssistantOutputCallback");
        boolean intent = implementsType(callback.getClass(), SERVICE + "IAssistantIntentCallback");
        if (!output && !intent) return;
        int requestGeneration = generation.get();
        if ("chat".equals(method)) pendingChat.set(callback);
        worker.execute(() -> {
            if ("chat".equals(method) && requestGeneration != generation.get()) return;
            try {
                if ("chat".equals(method)) {
                    Object record = call(args[0], "getConversationRecordBean");
                    if (record == null) throw new java.io.IOException("OpenAI currently supports text conversations and selected text. Start a text conversation first.");
                    JSONObject input = new JSONObject();
                    for (String field : new String[]{"Content", "ConversationId", "UniqueId", "UserRecordId", "FileUrl", "OssFileUrl", "HighlightText", "HighlightAroundContext", "SystemPrompt"}) {
                        Object value = call(record, "get" + field);
                        input.put(Character.toLowerCase(field.charAt(0)) + field.substring(1), value == null ? "" : value);
                    }
                    input.put("sessionId", sessionId).put("generation", requestGeneration);
                    JSONObject reply = new JSONObject(invokeProvider(method, input.toString()));
                    if (requestGeneration != generation.get()) return;
                    if (!pendingChat.compareAndSet(callback, null)) return;
                    String text = reply.getString("text");
                    Object result = staticCall(type(SERVICE + "AIAssistantOutputArgs"), "success", text, text.length());
                    call(result, "setQuestionId", reply.getString("questionId"));
                    call(result, "setReplyId", reply.getString("replyId"));
                    call(callback, "read", result);
                } else if ("initReadingAssistant".equals(method)) {
                    // NeoReader's document preflight gets local instructions, not a cloud upload.
                    String instructions = new JSONObject(invokeProvider(method, "{}")).getString("text");
                    call(callback, "read", staticCall(type(SERVICE + "AIAssistantOutputArgs"), "success", instructions, 0));
                } else if ("loadQuotaInfo".equals(method)) {
                    JSONObject json = new JSONObject(invokeProvider(method, "{}"));
                    Object permission = type("com.onyx.android.sdk.data.model.permission.PermissionInfoBean").getConstructor().newInstance();
                    permission.getClass().getField("enable").setBoolean(permission, true);
                    permission.getClass().getField("dayTimes").setLong(permission, json.getLong("dayTimes"));
                    permission.getClass().getField("allTimes").setLong(permission, json.getLong("allTimes"));
                    call(callback, "read", staticCall(type(SERVICE + "AIAssistantOutputArgs"), "permissionInfo", permission));
                } else if (intent) {
                    String input = args.length > 1 ? (String) call(args[0], "getContent") : "{}";
                    String result = reader && "loadConversationList".equals(method)
                        ? ReaderConversations.load(input, this::invokeProvider)
                        : invokeProvider(method, input);
                    Object reply = type(SERVICE + "AssistantIntentArgs").getConstructor(String.class).newInstance(result);
                    call(callback, "read", reply);
                } else {
                    throw new java.io.IOException("This native feature is not connected to OpenAI yet. Text conversations and selected text are supported.");
                }
            } catch (Throwable error) {
                if ("chat".equals(method) && requestGeneration != generation.get()) return;
                if ("chat".equals(method)) pendingChat.compareAndSet(callback, null);
                android.util.Log.w("BooxOpenAIHook", "Adapter action failed: " + method + " (" + error.getClass().getSimpleName() + ")");
                if (error instanceof java.io.IOException) android.util.Log.w("BooxOpenAIHook", error.getMessage());
                try {
                    String message = error instanceof java.io.IOException ? error.getMessage() : "OpenAI adapter error. Open BOOX OpenAI Setup to check the connection.";
                    if (output) call(callback, "read", staticCall(type(SERVICE + "AIAssistantOutputArgs"), "error", -2, message));
                    else call(callback, "read", staticCall(type(SERVICE + "AssistantIntentArgs"), "fail"));
                } catch (Throwable ignored) {}
            }
        });
    }
    private String invokeProvider(String method, String input) throws Exception {
        if (context == null) throw new java.io.IOException("Assistant context is not ready. Close and reopen the assistant.");
        Bundle response = context.getContentResolver().call(BRIDGE, method, input, null);
        if (response == null) throw new java.io.IOException("Open BOOX OpenAI Setup once, then reopen the assistant.");
        if (response.containsKey("error")) throw new java.io.IOException(response.getString("error"));
        return response.getString("json");
    }
    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private static boolean implementsType(Class<?> type, String name) {
        if (type == null) return false;
        if (type.getName().equals(name)) return true;
        for (Class<?> contract : type.getInterfaces()) if (implementsType(contract, name)) return true;
        return implementsType(type.getSuperclass(), name);
    }
    private static Object call(Object receiver, String name, Object... args) throws Exception {
        return invoke(receiver.getClass(), receiver, name, args);
    }
    private static Object staticCall(Class<?> type, String name, Object... args) throws Exception { return invoke(type, null, name, args); }
    private static Object invoke(Class<?> type, Object receiver, String name, Object[] args) throws Exception {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean match = true;
            Class<?>[] parameters = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null || parameters[i].isInstance(args[i]) || (parameters[i] == int.class && args[i] instanceof Integer)) continue;
                match = false;
            }
            if (match) { method.setAccessible(true); return method.invoke(receiver, args); }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
