package local.boox.openai;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Uses isolated history and a fake HTTPS connection. Never prints or retains API keys. */
public final class ValidationRunner extends Instrumentation {
    private SharedPreferences history;
    private SharedPreferences config;
    private AssistantProvider provider;
    private int passed, failed;
    private final StringBuilder results = new StringBuilder();
    private static volatile JSONObject lastRequest;
    private static volatile boolean block;
    private static volatile CountDownLatch started;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        try {
            URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) { return new FakeConnection(url); }
            } : null);
            Context context = new ContextWrapper(getTargetContext()) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    if ("openai_history".equals(name)) name = "validation_history";
                    if ("private_config".equals(name)) name = "validation_private_config";
                    if ("connection_settings".equals(name)) name = "validation_connection_settings";
                    if ("chatgpt_oauth".equals(name)) name = "validation_chatgpt_oauth";
                    return super.getSharedPreferences(name, mode);
                }
                @Override public String getPackageName() { return "local.boox.openai.validation"; }
            };
            context.getSharedPreferences("connection_settings", Context.MODE_PRIVATE).edit().clear().commit();
            history = context.getSharedPreferences("openai_history", Context.MODE_PRIVATE);
            config = context.getSharedPreferences("private_config", Context.MODE_PRIVATE);
            new KeyVault(context).save("synthetic-fixture-key-never-sent", "fixture-model");
            provider = new AssistantProvider();
            provider.attachInfo(context, new ProviderInfo());
            test("parser and sanitized errors", () -> ResponsesClient.selfTest());
            test("reading preflight stays local", () -> {
                JSONObject result = object("initReadingAssistant", "{}");
                check(result.getString("text").contains("selected passage"), "Reading instructions missing");
                check(lastRequest == null, "Reading preflight made a network request");
                check(history.getAll().isEmpty(), "Reading preflight changed history");
            });
            test("new topic can be activated by native UI", () -> {
                JSONObject created = object("createConversation", "{\"selected\":false}");
                check(!created.getBoolean("selected"), "Native UI skips topics already marked selected");
                check(created.getString("name").isEmpty(), "New topic cannot be auto-titled");
            });
            test("only one topic selected", () -> {
                create("a", "Alpha"); create("b", "Beta");
                JSONArray list = array("loadConversationList", "{}");
                int selected = 0;
                for (int i = 0; i < list.length(); i++) if (list.getJSONObject(i).optBoolean("selected")) selected++;
                check(selected == 1, "Expected one selected topic");
            });
            test("rename does not switch selected topic", () -> {
                create("a", "Alpha"); create("b", "Beta");
                call("updateConversation", "{\"uniqueId\":\"a\",\"name\":\"Renamed\"}");
                check("b".equals(object("loadCommonConfig", "{}").getJSONObject("conversationBean").getString("uniqueId")), "Rename changed selection");
            });
            test("topic search filters results", () -> {
                create("a", "Alpha"); create("b", "Beta");
                JSONArray list = array("loadConversationList", "{\"query\":\"alp\"}");
                check(list.length() == 1 && "a".equals(list.getJSONObject(0).getString("uniqueId")), "Search not applied");
            });
            test("topic paging has no duplicates", () -> {
                for (int i = 0; i < 5; i++) create("c" + i, "Topic " + i);
                JSONArray a = array("loadConversationList", "{\"page\":0,\"pageSize\":2}");
                JSONArray b = array("loadConversationList", "{\"page\":1,\"pageSize\":2}");
                check(a.length() == 2 && b.length() == 2, "Page size ignored");
                check(!a.getJSONObject(0).getString("uniqueId").equals(b.getJSONObject(0).getString("uniqueId")), "Page repeated");
            });
            test("NeoReader finds documents beyond the first topic page", () -> {
                JSONArray topics = new JSONArray();
                for (int i = 124; i >= 0; i--)
                    topics.put(new JSONObject().put("uniqueId", "c" + i).put("name", "Book " + i).put("docId", "doc" + i));
                history.edit().putString("conversations", topics.toString()).putString("selected", "c124").commit();
                JSONArray all = new JSONArray(ReaderConversations.load("{\"pageSize\":20}", this::call));
                check(all.length() == 125, "Older book topics were omitted");
                check("doc0".equals(all.getJSONObject(124).getString("docId")), "Oldest book could not be found");
            });
            test("record paging and deletion", () -> {
                create("a", "Alpha"); seedRecords(6);
                JSONArray page = array("loadRecordList", "{\"conversationId\":\"a\",\"pageSize\":2}");
                check("r5".equals(page.getJSONObject(0).getString("uniqueId")), "Records not newest first");
                page = array("loadRecordList", "{\"conversationId\":\"a\",\"pageSize\":2,\"lastDataId\":\"r4\"}");
                check("r3".equals(page.getJSONObject(0).getString("uniqueId")), "Cursor page incorrect");
                call("deleteRecord", "{\"conversationId\":\"a\",\"uniqueId\":\"r3\"}");
                check(stored().length() == 5, "Record not deleted");
                call("deleteConversation", "{\"uniqueId\":\"a\"}");
                check(!history.contains("records_a"), "Deleted conversation retained records");
            });
            test("unknown cursor does not repeat first page", () -> {
                create("a", "Alpha"); seedRecords(6);
                check(array("loadRecordList", "{\"conversationId\":\"a\",\"lastDataId\":\"missing\"}").length() == 0, "Unknown cursor repeated history");
            });
            test("chat request context and selected text", () -> {
                create("a", "Alpha");
                call("chat", "{\"conversationId\":\"a\",\"content\":\"First\"}");
                call("chat", "{\"conversationId\":\"a\",\"content\":\"Second\",\"highlightText\":\"Selected fixture\",\"highlightAroundContext\":\"Context fixture\",\"systemPrompt\":\"Be concise\"}");
                JSONArray messages = lastRequest.getJSONArray("input");
                check(messages.length() == 3, "Previous turn missing");
                check(messages.getJSONObject(2).getString("content").contains("Selected fixture"), "Selected text absent");
                check("Be concise".equals(lastRequest.getString("instructions")), "Instructions absent");
                check(!lastRequest.getBoolean("store"), "Remote storage requested");
                check(stored().length() == 4, "Conversation not persisted");
            });
            test("regenerate reuses question and excludes later history", () -> {
                create("a", "Alpha");
                JSONObject first = object("chat", "{\"conversationId\":\"a\",\"content\":\"First\"}");
                call("chat", "{\"conversationId\":\"a\",\"content\":\"Later\"}");
                JSONObject retry = new JSONObject().put("conversationId", "a").put("content", "First")
                    .put("uniqueId", first.getString("replyId")).put("userRecordId", first.getString("questionId"));
                JSONObject result = object("chat", retry.toString());
                check(first.getString("questionId").equals(result.getString("questionId")), "Regenerate duplicated question");
                check(lastRequest.getJSONArray("input").length() == 1, "Regenerate included future turns or old answer");
                check(stored().length() == 5, "Regenerate did not add exactly one alternative answer");
                check(stored().getJSONObject(2).getString("uniqueId").equals(result.getString("replyId")), "Alternative not placed beside original answer");
            });
            test("attachments and oversized text fail without a request", () -> {
                create("a", "Alpha"); lastRequest = null;
                check(provider.call("chat", "{\"conversationId\":\"a\",\"content\":\"test\",\"fileUrl\":\"fixture.pdf\"}", null).containsKey("error"), "Attachment accepted");
                String text = new String(new char[60001]).replace('\0', 'x');
                check(provider.call("chat", new JSONObject().put("conversationId", "a").put("content", text).toString(), null).containsKey("error"), "Oversized prompt accepted");
                check(lastRequest == null, "Unsupported input sent");
            });
            test("in-flight cancellation prevents history write", () -> {
                create("a", "Alpha"); block = true; started = new CountDownLatch(1);
                ExecutorService worker = Executors.newSingleThreadExecutor();
                try {
                    Future<Bundle> future = worker.submit(() -> provider.call("chat", "{\"conversationId\":\"a\",\"content\":\"Cancellation fixture\"}", null));
                    check(started.await(3, TimeUnit.SECONDS), "Request never started");
                    call("abort", "{}");
                    check(future.get(3, TimeUnit.SECONDS).containsKey("error"), "Cancelled request reported success");
                    check(stored().length() == 0, "Cancelled reply persisted");
                } finally { block = false; worker.shutdownNow(); }
            });
            test("Stop before request registration prevents network call", () -> {
                create("a", "Alpha");
                call("abort", "{\"sessionId\":\"fixture\",\"generation\":1}");
                Bundle result = provider.call("chat", "{\"conversationId\":\"a\",\"content\":\"Cancelled queued fixture\",\"sessionId\":\"fixture\",\"generation\":0}", null);
                check(result.containsKey("error") && lastRequest == null, "Stale request started after Stop");
                call("chat", "{\"conversationId\":\"a\",\"content\":\"New fixture\",\"sessionId\":\"fixture\",\"generation\":1}");
                check(stored().length() == 2, "New request after Stop failed");
            });
            test("deleting active topic prevents resurrection", () -> {
                create("a", "Alpha"); block = true; started = new CountDownLatch(1);
                ExecutorService worker = Executors.newSingleThreadExecutor();
                try {
                    Future<Bundle> future = worker.submit(() -> provider.call("chat", "{\"conversationId\":\"a\",\"content\":\"Deletion fixture\"}", null));
                    check(started.await(3, TimeUnit.SECONDS), "Request never started");
                    call("deleteConversation", "{\"uniqueId\":\"a\"}");
                    check(future.get(3, TimeUnit.SECONDS).containsKey("error"), "Deleted topic completed");
                    check(!history.contains("records_a"), "Deleted history recreated");
                } finally { block = false; worker.shutdownNow(); }
            });
            test("another app session cannot cancel an active reply", () -> {
                create("a", "Alpha"); block = true; started = new CountDownLatch(1);
                ExecutorService worker = Executors.newSingleThreadExecutor();
                try {
                    Future<Bundle> future = worker.submit(() -> provider.call("chat",
                        "{\"conversationId\":\"a\",\"content\":\"Reader session fixture\",\"sessionId\":\"reader-session\"}", null));
                    check(started.await(3, TimeUnit.SECONDS), "Request never started");
                    call("abort", "{\"sessionId\":\"assistant-session\",\"generation\":1}");
                    try { future.get(150, TimeUnit.MILLISECONDS); throw new AssertionError("Other session canceled the request"); }
                    catch (TimeoutException expected) {}
                    block = false;
                    check(!future.get(3, TimeUnit.SECONDS).containsKey("error"), "Reader request did not complete");
                    check(stored().length() == 2, "Reader history missing");
                } finally { block = false; worker.shutdownNow(); }
            });
            test("deleting a question during regenerate stops the reply", () -> {
                create("a", "Alpha");
                JSONObject first = object("chat", "{\"conversationId\":\"a\",\"content\":\"Question fixture\"}");
                JSONObject retry = new JSONObject().put("conversationId", "a").put("content", "Question fixture")
                    .put("userRecordId", first.getString("questionId")).put("uniqueId", first.getString("replyId"));
                block = true; started = new CountDownLatch(1);
                ExecutorService worker = Executors.newSingleThreadExecutor();
                try {
                    Future<Bundle> future = worker.submit(() -> provider.call("chat", retry.toString(), null));
                    check(started.await(3, TimeUnit.SECONDS), "Request never started");
                    call("deleteRecord", new JSONObject().put("conversationId", "a").put("uniqueId", first.getString("questionId")).toString());
                    check(future.get(3, TimeUnit.SECONDS).containsKey("error"), "Deleted question still received answer");
                    check(stored().length() == 1, "Regenerated answer persisted after deletion");
                } finally { block = false; worker.shutdownNow(); }
            });
        } catch (Throwable e) {
            failed++; results.append("RUNNER ERROR: ").append(e.getClass().getSimpleName()).append('\n');
        } finally {
            if (history != null) history.edit().clear().commit();
            if (config != null) config.edit().clear().commit();
            Bundle summary = new Bundle();
            summary.putString("stream", "\n" + results + "RESULT: " + passed + " passed; " + failed + " failed\n");
            finish(failed == 0 ? -1 : 0, summary);
        }
    }

    private interface Check { void run() throws Exception; }
    private void test(String name, Check test) {
        history.edit().clear().commit(); block = false; lastRequest = null;
        try { test.run(); passed++; results.append("PASS "); }
        catch (Throwable error) {
            failed++; results.append("FAIL ");
            if (error instanceof AssertionError) name += ": " + error.getMessage();
            else name += ": " + error.getClass().getSimpleName();
        }
        results.append(name).append('\n');
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private String call(String method, String json) throws Exception {
        Bundle result = provider.call(method, json, null);
        if (result.containsKey("error")) throw new IOException("Provider rejected synthetic test");
        return result.getString("json");
    }
    private JSONObject object(String method, String json) throws Exception { return new JSONObject(call(method, json)); }
    private JSONArray array(String method, String json) throws Exception { return new JSONArray(call(method, json)); }
    private void create(String id, String name) throws Exception {
        call("createConversation", new JSONObject().put("uniqueId", id).put("name", name).toString());
    }
    private JSONArray stored() throws Exception { return new JSONArray(history.getString("records_a", "[]")); }
    private void seedRecords(int count) throws Exception {
        JSONArray records = new JSONArray();
        for (int i = 0; i < count; i++) records.put(new JSONObject().put("uniqueId", "r" + i)
            .put("conversationId", "a").put("content", "Fixture " + i).put("ownerType", i % 2 == 0 ? "user" : "ai"));
        history.edit().putString("records_a", records.toString()).commit();
    }

    private static final class FakeConnection extends HttpsURLConnection {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private volatile boolean disconnected;
        FakeConnection(URL url) { super(url); }
        @Override public void setRequestProperty(String name, String value) { /* Do not retain authentication. */ }
        @Override public OutputStream getOutputStream() { return body; }
        @Override public int getResponseCode() throws IOException {
            try { lastRequest = new JSONObject(body.toString("UTF-8")); }
            catch (JSONException e) { throw new IOException("Invalid fixture JSON"); }
            if (block) {
                started.countDown();
                while (block && !disconnected) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { throw new IOException("Interrupted"); }
                }
            }
            if (disconnected) throw new IOException("Request stopped.");
            return 200;
        }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(("{\"status\":\"completed\",\"output\":[{\"type\":\"message\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"Fixture answer\"}]}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
        @Override public String getCipherSuite() { return "fixture"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
    }
}
