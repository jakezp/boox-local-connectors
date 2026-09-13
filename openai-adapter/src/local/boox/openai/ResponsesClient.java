package local.boox.openai;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

final class ResponsesClient {
    private volatile HttpsURLConnection active;
    private volatile boolean cancelled;
    void cancel() {
        cancelled = true;
        HttpsURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }
    boolean isCancelled() { return cancelled; }
    static JSONObject request(String model, String input) throws JSONException {
        return new JSONObject().put("model", model).put("input", input)
            .put("store", false).put("max_output_tokens", 128);
    }
    static String output(JSONObject response) throws Exception {
        if (!"completed".equals(response.optString("status")))
            throw new IOException("The response did not complete. Try a shorter request or another model.");
        StringBuilder result = new StringBuilder();
        JSONArray items = response.optJSONArray("output");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type"))) continue;
            JSONArray contents = item.optJSONArray("content");
            if (contents == null) continue;
            for (int j = 0; j < contents.length(); j++) {
                JSONObject content = contents.optJSONObject(j);
                if (content == null) continue;
                if ("refusal".equals(content.optString("type"))) throw new IOException("OpenAI declined this request.");
                if ("output_text".equals(content.optString("type"))) result.append(content.optString("text"));
            }
        }
        if (result.length() == 0) throw new IOException("OpenAI returned no text.");
        return result.toString();
    }
    static String error(int code, String body) {
        if (code == 401) return "API key rejected. Check the key and save it again.";
        if (code == 403) return "Your API project is not permitted to use this request or model.";
        if (code == 429) {
            try {
                JSONObject error = new JSONObject(body).optJSONObject("error");
                if (error != null && "insufficient_quota".equals(error.optString("code")))
                    return "API billing credit or quota is exhausted. Check your OpenAI API project billing.";
            } catch (JSONException ignored) {}
            return "API rate or quota limit reached. Check API billing and try again later.";
        }
        if (code == 400 || code == 404) return "The model or request is unavailable for this API project. Check the model ID.";
        return "OpenAI request failed (HTTP " + code + "). Try again later.";
    }
    String test(String key, String model) throws Exception {
        return complete(key, request(model, "Reply with exactly: OpenAI connection working."));
    }
    String complete(String key, JSONObject request) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://api.openai.com/v1/responses").openConnection();
        active = connection;
        try {
            if (cancelled) throw new IOException("Request stopped.");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = readBounded(stream);
            if (status < 200 || status >= 300) throw new IOException(error(status, body));
            return output(new JSONObject(body));
        } finally { active = null; connection.disconnect(); }
    }
    private static String readBounded(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 1048576) throw new IOException("Unexpectedly large API response.");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    static void selfTest() throws Exception {
        JSONObject request = request("test-model", "Quote: \"x\"\nUnicode: café");
        if (request.getBoolean("store") || request.getInt("max_output_tokens") != 128) throw new AssertionError("request settings");
        if (!new JSONObject(request.toString()).getString("input").contains("café")) throw new AssertionError("encoding");
        JSONObject response = new JSONObject("{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\"},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello \"},{\"type\":\"output_text\",\"text\":\"world\"}]}]}");
        if (!"Hello world".equals(output(response))) throw new AssertionError("output parsing");
        response.put("status", "incomplete");
        try { output(response); throw new AssertionError("incomplete accepted"); } catch (IOException expected) {}
        if (error(401, "secret-fixture").contains("secret-fixture")) throw new AssertionError("error disclosure");
        if (!error(429, "{\"error\":{\"code\":\"insufficient_quota\"}}").contains("billing")) throw new AssertionError("billing error");
    }
}
