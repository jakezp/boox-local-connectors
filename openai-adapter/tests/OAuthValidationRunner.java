package local.boox.openai;

import android.app.Instrumentation;
import android.content.*;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Synthetic credentials, separate preference namespace and Keystore alias; all HTTPS is fake. */
public final class OAuthValidationRunner extends Instrumentation {
    private Context fixture;
    private OAuthVault vault;
    private ConnectionSettings settings;
    private int passed, failed;
    private final StringBuilder results = new StringBuilder();
    private static final Queue<Response> responses = new ConcurrentLinkedQueue<>();
    private static final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private static JSONObject lastBody;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        try {
            URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) throws IOException { return new Fake(url); }
            } : null);
            fixture = new ContextWrapper(getTargetContext()) {
                @Override public String getPackageName() { return "local.boox.openai.oauth.validation"; }
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences("oauth_validation_" + name, mode);
                }
            };
            vault = new OAuthVault(fixture); settings = new ConnectionSettings(fixture);
            new KeyVault(fixture).save("synthetic-api-fixture-never-sent", "api-fixture-model");
            test("OAuth credentials reject missing fields and account", () -> {
                rejects(() -> CodexOAuth.credentials(new JSONObject(), null));
                rejects(() -> CodexOAuth.credentials(new JSONObject().put("access_token", "invalid-fixture-token")
                    .put("refresh_token", "refresh-fixture").put("expires_in", 3600), null));
                OAuthVault.Credentials credentials = credentials("one", 3600);
                check("account-fixture".equals(credentials.account), "Account extraction failed");
            });
            test("device login validates response and honors pre-cancellation", () -> {
                enqueue(200, "{\"device_auth_id\":\"id\",\"user_code\":\"ABCD-1234\",\"interval\":\"1\"}");
                check(new CodexOAuth(new OpenAIHttp()).start().interval == 1, "Polling interval not parsed");
                enqueue(200, "{}"); rejects(() -> new CodexOAuth(new OpenAIHttp()).start());
                OpenAIHttp stopped = new OpenAIHttp(); stopped.cancel();
                int before = paths.size(); rejects(() -> new CodexOAuth(stopped).start());
                check(paths.size() == before, "Canceled login opened network");
            });
            test("device login exchanges code after pending response", () -> {
                enqueue(403, "{}");
                enqueue(200, "{\"authorization_code\":\"fixture-code\",\"code_verifier\":\"fixture-verifier\"}");
                enqueue(200, tokens("one", 3600).toString());
                OAuthVault.Credentials result = new CodexOAuth(new OpenAIHttp()).await(new CodexOAuth.DeviceCode("fixture", "ABCD-1234", 1));
                check("account-fixture".equals(result.account) && paths.size() == 3, "Device exchange failed");
                check(paths.get(2).endsWith("/oauth/token"), "Wrong token exchange endpoint");
            });
            test("device denial and expiry never exchange tokens", () -> {
                enqueue(400, "{\"error\":\"access_denied\"}");
                rejects(() -> new CodexOAuth(new OpenAIHttp()).await(new CodexOAuth.DeviceCode("fixture", "ABCD-1234", 1)));
                enqueue(400, "{\"error\":\"expired_token\"}");
                rejects(() -> new CodexOAuth(new OpenAIHttp()).await(new CodexOAuth.DeviceCode("fixture", "ABCD-1234", 1)));
                check(paths.size() == 2, "Denied authorization exchanged tokens");
            });
            test("canceled and superseded logins cannot save credentials", () -> {
                long old = vault.beginLogin(); vault.cancelLogin(old);
                rejects(() -> vault.saveLogin(old, credentials("one", 3600)));
                long next = vault.beginLogin(); long latest = vault.beginLogin();
                rejects(() -> vault.saveLogin(next, credentials("one", 3600)));
                vault.saveLogin(latest, credentials("two", 3600));
                check(vault.session().credentials.access.equals(credentials("two", 3600).access), "Wrong login saved");
            });
            test("OAuth tokens are encrypted and API credentials survive logout", () -> {
                login("one", 3600);
                String stored = fixture.getSharedPreferences("chatgpt_oauth", 0).getAll().toString();
                check(!stored.contains("refresh-one-fixture") && !stored.contains(credentials("one", 3600).access), "Plaintext tokens stored");
                vault.clear();
                check(!vault.hasSession(), "Logout retained session");
                check("synthetic-api-fixture-never-sent".equals(new KeyVault(fixture).read()), "Logout erased API credentials");
            });
            test("fresh tokens are reused without a network request", () -> {
                login("one", 3600); OAuthVault.Session session = vault.session();
                check(vault.fresh(session, new OpenAIHttp(), false).credentials.access.equals(session.credentials.access), "Fresh token changed");
                check(paths.isEmpty(), "Fresh token refreshed");
            });
            test("concurrent refresh is coalesced and rotation persisted", () -> {
                login("one", 1); OAuthVault.Session session = vault.session();
                Response response = enqueue(200, tokens("two", 3600).toString()); response.block = true;
                ExecutorService workers = Executors.newFixedThreadPool(2);
                try {
                    Future<OAuthVault.Session> first = workers.submit(() -> vault.fresh(session, new OpenAIHttp(), false));
                    check(response.started.await(3, TimeUnit.SECONDS), "Refresh not started");
                    Future<OAuthVault.Session> second = workers.submit(() -> vault.fresh(session, new OpenAIHttp(), false));
                    response.release.countDown();
                    check(first.get(3, TimeUnit.SECONDS).credentials.access.equals(second.get(3, TimeUnit.SECONDS).credentials.access), "Refresh race");
                    check(paths.size() == 1, "Refresh used rotated token twice");
                    check(vault.session().credentials.refresh.equals("refresh-two-fixture"), "New refresh token not saved");
                } finally { response.release.countDown(); workers.shutdownNow(); }
            });
            test("logout during refresh prevents credential resurrection", () -> {
                login("one", 1); OAuthVault.Session session = vault.session();
                Response response = enqueue(200, tokens("two", 3600).toString()); response.block = true;
                ExecutorService workers = Executors.newSingleThreadExecutor();
                try {
                    Future<?> refresh = workers.submit(() -> { try { vault.fresh(session, new OpenAIHttp(), false); throw new AssertionError("Stale refresh saved"); } catch (IOException expected) {} catch (Exception error) { throw new RuntimeException(error); } });
                    check(response.started.await(3, TimeUnit.SECONDS), "Refresh not started");
                    vault.clear(); response.release.countDown(); refresh.get(3, TimeUnit.SECONDS);
                    check(!vault.hasSession(), "Logout was undone");
                } finally { response.release.countDown(); workers.shutdownNow(); }
            });
            test("failed refresh preserves API mode and sanitizes secrets", () -> {
                login("one", 1); enqueue(400, "{\"error\":\"invalid_grant\",\"private\":\"secret-fixture\"}");
                try { vault.fresh(vault.session(), new OpenAIHttp(), false); throw new AssertionError("Invalid grant accepted"); }
                catch (IOException error) { check(!error.getMessage().contains("secret-fixture"), "Token error leaked body"); }
                check(ConnectionSettings.API.equals(settings.mode()), "Failed auth switched mode");
            });
            test("activation requires successful account and model test", () -> {
                login("one", 3600); settings.saveChatModel("chat-fixture-model");
                rejects(() -> settings.activate(ConnectionSettings.CHATGPT));
                settings.verified("chat-fixture-model", vault.session()); settings.activate(ConnectionSettings.CHATGPT);
                ConnectionSettings.Snapshot saved = settings.snapshot();
                settings.activate(ConnectionSettings.API);
                check(ConnectionSettings.CHATGPT.equals(saved.mode) && "chat-fixture-model".equals(saved.model), "Request settings changed");
                check("api-fixture-model".equals(settings.model(ConnectionSettings.API)), "API model was replaced");
            });
            test("subscription payload retains context and omits API-only limits", () -> {
                JSONObject source = request().put("instructions", "Reading fixture").put("max_output_tokens", 2048);
                source.getJSONArray("input").put(new JSONObject().put("role", "assistant").put("content", "Earlier answer"));
                JSONObject body = CodexResponsesClient.request(source);
                check(body.getBoolean("stream") && !body.getBoolean("store") && !body.has("max_output_tokens"), "Wrong subscription options");
                check("Reading fixture".equals(body.getString("instructions")), "Instructions lost");
                check("output_text".equals(body.getJSONArray("input").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type")), "Assistant content not translated");
            });
            test("SSE handles split UTF-8, CRLF and completed output without duplication", () -> {
                String events = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"café 🌿\"}\r\n\r\n" + completed("café 🌿");
                check("café 🌿".equals(CodexResponsesClient.readEvents(oneByte(events), () -> {})), "Stream text corrupted or duplicated");
            });
            test("SSE handles multiline data and final event without blank line", () -> {
                String events = "event: message\ndata: {\"type\":\"response.output_text.delta\",\ndata: \"delta\":\"Hello\"}\n\n"
                    + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}";
                check("Hello".equals(CodexResponsesClient.readEvents(oneByte(events), () -> {})), "SSE framing failed");
            });
            test("SSE rejects truncation, incomplete, failure and refusal", () -> {
                rejects(() -> CodexResponsesClient.readEvents(oneByte("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}\n\n"), () -> {}));
                for (String type : new String[]{"response.incomplete", "response.failed", "error", "response.refusal.delta"})
                    rejects(() -> CodexResponsesClient.readEvents(oneByte("data: {\"type\":\"" + type + "\"}\n\n"), () -> {}));
            });
            test("SSE cancellation is checked before returning completed text", () -> {
                rejects(() -> CodexResponsesClient.readEvents(oneByte(completed("Fixture")), () -> { throw new IOException("Stopped fixture"); }));
            });
            test("HTTP 401 refreshes once before retrying subscription request", () -> {
                login("one", 3600); enqueue(401, "{}"); enqueue(200, tokens("two", 3600).toString()); enqueue(200, completed("Recovered"));
                check("Recovered".equals(new CodexResponsesClient(fixture).complete(request())), "401 recovery failed");
                check(paths.size() == 3 && paths.get(1).endsWith("/oauth/token"), "Incorrect retry sequence");
                check(paths.stream().noneMatch(p -> p.contains("api.openai.com")), "Subscription fell back to API");
            });
            test("subscription quota errors do not refresh or fall back", () -> {
                login("one", 3600); enqueue(429, "{\"secret\":\"fixture\"}");
                rejects(() -> new CodexResponsesClient(fixture).complete(request()));
                check(paths.size() == 1 && paths.get(0).startsWith("chatgpt.com/"), "Quota caused another request");
            });
            test("logout invalidates an already created subscription client", () -> {
                login("one", 3600); CodexResponsesClient client = new CodexResponsesClient(fixture); vault.clear();
                rejects(() -> client.complete(request())); check(paths.isEmpty(), "Logged-out client sent request");
            });
            test("model catalog uses account endpoint and excludes hidden entries", () -> {
                login("one", 3600); enqueue(200, "{\"models\":[{\"slug\":\"hidden-model\",\"visibility\":\"hide\"},{\"slug\":\"model-b\",\"priority\":2},{\"slug\":\"model-a\",\"priority\":1}]}");
                List<String> models = new CodexResponsesClient(fixture).models();
                check(models.equals(Arrays.asList("model-a", "model-b")), "Models not filtered/sorted");
                check(paths.get(0).contains("/codex/models"), "Wrong model endpoint");
            });
            test("an untested replacement account cannot use active subscription mode", () -> {
                login("one", 3600); settings.verified("chat-fixture-model", vault.session()); settings.activate(ConnectionSettings.CHATGPT);
                OAuthVault.Credentials previous = credentials("two", 3600);
                vault.saveLogin(vault.beginLogin(), new OAuthVault.Credentials(previous.access, previous.refresh, "different-account", previous.plan, previous.expires));
                rejects(() -> settings.snapshot());
                check(paths.isEmpty(), "Untested account reached network");
            });
            test("native provider stores subscription replies with the captured model", () -> {
                AssistantProvider provider = provider();
                Response response = enqueue(200, completed("Native subscription fixture")); response.block = true;
                ExecutorService workers = Executors.newSingleThreadExecutor();
                try {
                    Future<Bundle> result = workers.submit(() -> provider.call("chat", "{\"conversationId\":\"fixture-topic\",\"content\":\"Synthetic native question\"}", null));
                    check(response.started.await(3, TimeUnit.SECONDS), "Subscription request did not start");
                    settings.activate(ConnectionSettings.API); response.release.countDown();
                    check(!result.get(3, TimeUnit.SECONDS).containsKey("error"), "Subscription reply failed");
                    JSONArray records = new JSONArray(fixture.getSharedPreferences("openai_history", 0).getString("records_fixture-topic", "[]"));
                    check(records.length() == 2, "Native history was not stored");
                    check("chatgpt".equals(records.getJSONObject(1).getString("connectionMode")), "Mode switch mislabeled reply");
                    check(records.getJSONObject(1).getString("modelName").contains("chat-fixture-model"), "Reply model changed mid-request");
                } finally { response.release.countDown(); workers.shutdownNow(); }
            });
            test("logout during a native reply prevents history commit", () -> {
                AssistantProvider provider = provider();
                Response response = enqueue(200, completed("Must not persist")); response.block = true;
                ExecutorService workers = Executors.newSingleThreadExecutor();
                try {
                    Future<Bundle> result = workers.submit(() -> provider.call("chat", "{\"conversationId\":\"fixture-topic\",\"content\":\"Synthetic canceled question\"}", null));
                    check(response.started.await(3, TimeUnit.SECONDS), "Subscription request did not start");
                    vault.clear(); response.release.countDown();
                    check(result.get(3, TimeUnit.SECONDS).containsKey("error"), "Logged-out reply completed");
                    check(!fixture.getSharedPreferences("openai_history", 0).contains("records_fixture-topic"), "Logged-out reply persisted");
                } finally { response.release.countDown(); workers.shutdownNow(); }
            });
            test("publishing a login invalidates sessions captured while login was pending", () -> {
                login("one", 3600);
                long pending = vault.beginLogin();
                OAuthVault.Session previous = vault.session();
                vault.saveLogin(pending, credentials("two", 3600));
                check(!vault.isCurrent(previous), "Old credentials remained current after login");
                rejects(() -> vault.fresh(previous, new OpenAIHttp(), false));
                check(paths.isEmpty(), "Superseded credentials started a refresh");
            });
            test("connection snapshots cannot silently adopt a later login", () -> {
                login("one", 3600);
                ConnectionSettings.Snapshot captured = settings.snapshot(ConnectionSettings.CHATGPT, "chat-fixture-model");
                login("two", 3600);
                rejects(() -> captured.client());
                check(paths.isEmpty(), "Captured request adopted a new account");
            });
            test("configuration tab survives reopening without activating subscription mode", () -> {
                settings.configure(ConnectionSettings.CHATGPT);
                ConnectionSettings reopened = new ConnectionSettings(fixture);
                check(ConnectionSettings.CHATGPT.equals(reopened.configuredMode()), "ChatGPT tab was forgotten");
                check(ConnectionSettings.API.equals(reopened.mode()), "Viewing a tab changed billing mode");
            });
            test("pending device code survives vault recreation without extending expiry", () -> {
                long revision = vault.beginLogin();
                CodexOAuth.DeviceCode original = new CodexOAuth.DeviceCode("pending-device-fixture", "ZXCV-4321", 2);
                vault.savePending(revision, original);
                OAuthVault.PendingLogin restored = new OAuthVault(fixture).pending();
                check(restored.revision == revision && restored.code.id.equals(original.id) &&
                    restored.code.code.equals(original.code), "Pending login changed on reopen");
                check(restored.code.deadline <= original.deadline && restored.code.expiresAt <= original.expiresAt,
                    "Reopening extended the login lifetime");
                String storage = fixture.getSharedPreferences("chatgpt_oauth", 0).getAll().toString();
                check(!storage.contains(original.id) && !storage.contains(original.code), "Pending code stored in plaintext");
            });
            test("expired pending code is rejected and removed", () -> {
                long revision = vault.beginLogin();
                vault.savePending(revision, new CodexOAuth.DeviceCode("expired-fixture", "ZXCV-4321", 1,
                    android.os.SystemClock.elapsedRealtime() - 1000, System.currentTimeMillis() - 1000));
                rejects(() -> new OAuthVault(fixture).pending());
                check(vault.pending() == null, "Expired pending login retained");
            });
            test("cancellation and replacement remove only the matching pending login", () -> {
                long old = vault.beginLogin();
                vault.savePending(old, new CodexOAuth.DeviceCode("old-fixture", "ZXCV-4321", 1));
                long current = vault.beginLogin();
                check(vault.pending() == null, "New login kept old code");
                vault.savePending(current, new CodexOAuth.DeviceCode("new-fixture", "ASDF-1234", 1));
                vault.cancelLogin(old);
                check(vault.pending().revision == current, "Old cancel removed new code");
                vault.cancelLogin(current);
                check(vault.pending() == null, "Cancel retained code");
                rejects(() -> vault.savePending(current, new CodexOAuth.DeviceCode("stale-fixture", "ASDF-1234", 1)));
            });
            test("token refresh preserves pending login and successful login removes it", () -> {
                login("one", 1);
                long revision = vault.beginLogin();
                vault.savePending(revision, new CodexOAuth.DeviceCode("pending-fixture", "ZXCV-4321", 1));
                enqueue(200, tokens("two", 3600).toString());
                vault.fresh(vault.session(), new OpenAIHttp(), false);
                check(vault.pending() != null, "Refresh removed pending login");
                vault.saveLogin(revision, credentials("three", 3600));
                check(vault.pending() == null && vault.hasSession(), "Completed login did not clear pending code");
            });
        } catch (Throwable error) { failed++; results.append("RUNNER ERROR: ").append(error.getClass().getSimpleName()).append('\n'); }
        finally {
            try { if (vault != null) vault.clear(); } catch (Exception ignored) {}
            if (fixture != null) for (String name : new String[]{"chatgpt_oauth", "connection_settings", "private_config", "openai_history"}) fixture.getSharedPreferences(name, 0).edit().clear().commit();
            Bundle summary = new Bundle(); summary.putString("stream", "\n" + results + "RESULT: " + passed + " passed; " + failed + " failed\n");
            finish(failed == 0 ? -1 : 0, summary);
        }
    }
    private interface Check { void run() throws Exception; }
    private void test(String name, Check action) {
        try {
            responses.clear(); paths.clear(); lastBody = null; vault.clear();
            fixture.getSharedPreferences("connection_settings", 0).edit().clear().commit();
            fixture.getSharedPreferences("openai_history", 0).edit().clear().commit();
            action.run(); check(responses.isEmpty(), "Unused fixture responses"); passed++; results.append("PASS ");
        } catch (Throwable error) {
            failed++; results.append("FAIL ");
            name += ": " + (error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName());
        }
        results.append(name).append('\n');
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(Check action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    private void login(String version, int seconds) throws Exception { vault.saveLogin(vault.beginLogin(), credentials(version, seconds)); }
    private AssistantProvider provider() throws Exception {
        login("one", 3600);
        settings.verified("chat-fixture-model", vault.session()); settings.activate(ConnectionSettings.CHATGPT);
        AssistantProvider provider = new AssistantProvider(); provider.attachInfo(fixture, new ProviderInfo());
        Bundle created = provider.call("createConversation", "{\"uniqueId\":\"fixture-topic\",\"name\":\"Synthetic OAuth fixture\"}", null);
        check(!created.containsKey("error"), "Could not create fixture conversation");
        return provider;
    }
    private static OAuthVault.Credentials credentials(String version, int seconds) throws Exception { return CodexOAuth.credentials(tokens(version, seconds), null); }
    private static JSONObject tokens(String version, int seconds) throws Exception {
        JSONObject claims = new JSONObject().put("https://api.openai.com/auth", new JSONObject().put("chatgpt_account_id", "account-fixture").put("chatgpt_plan_type", "plus"));
        String jwt = "fixture." + Base64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING) + "." + version;
        return new JSONObject().put("access_token", jwt).put("refresh_token", "refresh-" + version + "-fixture").put("expires_in", seconds);
    }
    private static JSONObject request() throws Exception {
        return new JSONObject().put("model", "chat-fixture-model").put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", "Fixture question")));
    }
    private static String completed(String text) throws Exception {
        JSONObject response = new JSONObject().put("status", "completed").put("output", new JSONArray().put(new JSONObject().put("type", "message")
            .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", text)))));
        return "data: " + new JSONObject().put("type", "response.completed").put("response", response) + "\n\n";
    }
    private static InputStream oneByte(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) { return super.read(bytes, offset, Math.min(1, length)); }
        };
    }
    private static Response enqueue(int status, String body) { Response r = new Response(status, body); responses.add(r); return r; }
    private static final class Response {
        final int status; final String body;
        boolean block;
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Response(int status, String body) { this.status = status; this.body = body; }
    }
    private static final class Fake extends HttpsURLConnection {
        final Response response;
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        volatile boolean disconnected;
        Fake(URL url) throws IOException {
            super(url); paths.add(url.getHost() + url.getPath());
            response = responses.poll(); if (response == null) throw new IOException("Unexpected fixture request");
        }
        @Override public void setRequestProperty(String key, String value) { /* Never retain credentials. */ }
        @Override public OutputStream getOutputStream() { return body; }
        @Override public int getResponseCode() throws IOException {
            if (body.size() > 0 && body.toByteArray()[0] == '{') try { lastBody = new JSONObject(body.toString("UTF-8")); } catch (JSONException ignored) {}
            response.started.countDown();
            if (response.block) try { if (!response.release.await(4, TimeUnit.SECONDS)) throw new IOException("Fixture timeout"); } catch (InterruptedException error) { throw new IOException("Fixture interrupted"); }
            if (disconnected) throw new IOException("Fixture disconnected");
            return response.status;
        }
        @Override public InputStream getInputStream() { return oneByte(response.body); }
        @Override public InputStream getErrorStream() { return oneByte(response.body); }
        @Override public void disconnect() { disconnected = true; response.release.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
        @Override public String getCipherSuite() { return "fixture"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
    }
}
