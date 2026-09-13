package local.boox.openai;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Direct subscription transport. Credentials are refreshed only before any output. */
final class CodexResponsesClient implements ReplyClient {
    private static final String BASE = "https://chatgpt.com/backend-api/codex";
    // Models endpoint schema compatibility, matched to the reviewed Codex release.
    private static final String CATALOG_VERSION = "0.154.0";
    private final OAuthVault vault;
    private final OpenAIHttp http = new OpenAIHttp();
    private OAuthVault.Session session;

    CodexResponsesClient(Context context) throws Exception {
        this(context, new OAuthVault(context).session());
    }
    CodexResponsesClient(Context context, OAuthVault.Session session) throws Exception {
        vault = new OAuthVault(context);
        vault.requireCurrent(session);
        this.session = session;
    }
    @Override public void cancel() { http.cancel(); }
    @Override public boolean isCancelled() { return http.isCancelled() || !vault.isCurrent(session); }
    private void check() throws IOException { http.check(); vault.requireCurrent(session); }

    @Override public String complete(JSONObject request) throws Exception {
        JSONObject body = request(request);
        for (int attempt = 0; attempt < 2; attempt++) {
            session = vault.fresh(session, http, attempt == 1);
            check();
            HttpsURLConnection connection = http.open(BASE + "/responses", "POST", "application/json",
                body.toString().getBytes(StandardCharsets.UTF_8), headers(true));
            try {
                connection.setReadTimeout(120000);
                int status = connection.getResponseCode();
                if (status == 401 && attempt == 0) continue;
                if (status < 200 || status >= 300) throw failure(status);
                String answer = readEvents(connection.getInputStream(), this::check);
                check();
                return answer;
            } finally { http.finish(connection); }
        }
        throw failure(401);
    }
    List<String> models() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            session = vault.fresh(session, http, attempt == 1);
            check();
            HttpsURLConnection connection = http.open(BASE + "/models?client_version=" + CATALOG_VERSION,
                "GET", null, null, headers(false));
            try {
                int status = connection.getResponseCode();
                if (status == 401 && attempt == 0) continue;
                if (status < 200 || status >= 300) throw failure(status);
                JSONArray items = new JSONObject(OpenAIHttp.read(connection.getInputStream())).getJSONArray("models");
                List<JSONObject> visible = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if ("hide".equals(item.optString("visibility")) || "hidden".equals(item.optString("visibility"))) continue;
                    if (ConnectionSettings.validModel(item.optString("slug"))) visible.add(item);
                }
                visible.sort(Comparator.comparingInt(item -> item.optInt("priority", 999)));
                List<String> models = new ArrayList<>();
                for (JSONObject item : visible) if (!models.contains(item.getString("slug"))) models.add(item.getString("slug"));
                check();
                if (models.isEmpty()) throw new IOException("No ChatGPT models were listed for this account. Check your Codex access.");
                return models;
            } finally { http.finish(connection); }
        }
        throw failure(401);
    }
    private Map<String, String> headers(boolean stream) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + session.credentials.access);
        headers.put("chatgpt-account-id", session.credentials.account);
        headers.put("originator", "boox_openai");
        headers.put("OpenAI-Beta", "responses=experimental");
        headers.put("Accept", stream ? "text/event-stream" : "application/json");
        if (stream) headers.put("session_id", UUID.randomUUID().toString());
        return headers;
    }
    static JSONObject request(JSONObject source) throws Exception {
        JSONArray input = source.getJSONArray("input"), messages = new JSONArray();
        for (int i = 0; i < input.length(); i++) {
            JSONObject message = input.getJSONObject(i);
            String role = message.getString("role");
            if (!"user".equals(role) && !"assistant".equals(role))
                throw new IOException("Unsupported conversation role.");
            JSONObject content = new JSONObject().put("type", "assistant".equals(role) ? "output_text" : "input_text")
                .put("text", message.getString("content"));
            JSONObject mapped = new JSONObject().put("type", "message").put("role", role)
                .put("content", new JSONArray().put(content));
            if ("assistant".equals(role)) mapped.put("status", "completed");
            messages.put(mapped);
        }
        String instructions = source.optString("instructions").trim();
        if (instructions.isEmpty()) instructions = "You are a helpful assistant. Answer the user's question clearly.";
        return new JSONObject().put("model", source.getString("model")).put("input", messages)
            .put("instructions", instructions).put("store", false).put("stream", true);
    }
    interface Check { void run() throws IOException; }
    static String readEvents(InputStream stream, Check check) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10 * 60 * 1000;
        // Limit the entire stream, including reasoning and oversized individual lines.
        InputStream bounded = new FilterInputStream(stream) {
            private int total;
            private void count(int amount) throws IOException {
                if (amount > 0 && (total += amount) > 8 * 1048576)
                    throw new IOException("ChatGPT reply was too large. Ask for a shorter answer.");
            }
            @Override public int read() throws IOException { int value = super.read(); count(value < 0 ? 0 : 1); return value; }
            @Override public int read(byte[] bytes, int offset, int size) throws IOException {
                int amount = in.read(bytes, offset, size); count(amount); return amount;
            }
        };
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            SortedMap<String, StringBuilder> texts = new TreeMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                check.run();
                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("ChatGPT reply timed out. Try a shorter request.");
                if (line.startsWith("\uFEFF")) line = line.substring(1);
                if (line.isEmpty()) {
                    if (data.length() != 0) {
                        String result = event(data.toString(), texts);
                        data.setLength(0);
                        if (result != null) { check.run(); return result; }
                    }
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(line.startsWith("data: ") ? 6 : 5));
                }
            }
            if (data.length() != 0) {
                String result = event(data.toString(), texts);
                if (result != null) { check.run(); return result; }
            }
        }
        throw new IOException("ChatGPT connection ended before the answer completed. Try again.");
    }
    private static String event(String data, SortedMap<String, StringBuilder> texts) throws Exception {
        if ("[DONE]".equals(data)) return null;
        JSONObject event;
        try { event = new JSONObject(data); }
        catch (JSONException error) { throw new IOException("ChatGPT returned an unexpected response. Try again."); }
        String type = event.optString("type");
        if (type.startsWith("response.refusal"))
            throw new IOException("ChatGPT declined this request.");
        if ("error".equals(type) || "response.failed".equals(type) || "response.incomplete".equals(type))
            throw new IOException("ChatGPT could not complete this reply. Try a shorter request or another model.");
        String index = String.format(Locale.ROOT, "%08d:%08d", event.optInt("output_index"), event.optInt("content_index"));
        if ("response.output_text.delta".equals(type)) {
            if (!texts.containsKey(index)) texts.put(index, new StringBuilder());
            texts.get(index).append(event.optString("delta"));
        } else if ("response.output_text.done".equals(type)) {
            texts.put(index, new StringBuilder(event.optString("text")));
        }
        int length = 0;
        for (StringBuilder text : texts.values()) length += text.length();
        if (length > 1048576) throw new IOException("ChatGPT reply was too large. Ask for a shorter answer.");
        if ("response.completed".equals(type)) {
            JSONObject response = event.optJSONObject("response");
            if (response == null || !"completed".equals(response.optString("status")))
                throw new IOException("ChatGPT did not confirm a completed answer.");
            JSONArray output = response.optJSONArray("output");
            if (output != null && output.length() > 0) return ResponsesClient.output(response);
            StringBuilder result = new StringBuilder();
            for (StringBuilder text : texts.values()) result.append(text);
            if (result.length() == 0) throw new IOException("ChatGPT returned no text.");
            return result.toString();
        }
        return null;
    }
    static IOException failure(int status) {
        if (status == 401) return new IOException("ChatGPT sign-in was rejected. Sign in again in BOOX OpenAI Setup.");
        if (status == 403) return new IOException("This ChatGPT account cannot use this request or model. Check your Codex access.");
        if (status == 429) return new IOException("ChatGPT usage limit reached. Wait for your limit to reset or choose API mode yourself.");
        if (status == 400 || status == 404) return new IOException("This ChatGPT model or request is unavailable. Refresh models in BOOX OpenAI Setup.");
        return new IOException("ChatGPT request failed (HTTP " + status + "). Try again later.");
    }
}
