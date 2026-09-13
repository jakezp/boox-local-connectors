package local.boox.openai;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** One cancellable exchange at a time; never follows redirects with credentials. */
final class OpenAIHttp {
    private volatile HttpsURLConnection active;
    private volatile boolean cancelled;

    void cancel() {
        cancelled = true;
        HttpsURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }
    boolean isCancelled() { return cancelled; }
    void check() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Request stopped.");
    }
    HttpsURLConnection open(String address, String method, String contentType,
                            byte[] body, Map<String, String> headers) throws IOException {
        check();
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol()) ||
            !("auth.openai.com".equals(url.getHost()) || "chatgpt.com".equals(url.getHost())))
            throw new IOException("Unexpected OpenAI connection address.");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        active = connection;
        try {
            check();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("User-Agent", "boox-openai/0.9 (Android)");
            if (headers != null) for (Map.Entry<String, String> header : headers.entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            check();
            return connection;
        } catch (IOException error) {
            finish(connection);
            check();
            throw new IOException("Could not reach OpenAI. Check your internet connection and try again.");
        }
    }
    Result exchange(String address, String contentType, String body) throws IOException {
        HttpsURLConnection connection = open(address, "POST", contentType,
            body.getBytes(StandardCharsets.UTF_8), null);
        try {
            int status = connection.getResponseCode();
            String text = read(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
            check();
            return new Result(status, text);
        } catch (IOException error) {
            check();
            throw new IOException("Could not complete OpenAI sign-in. Check your connection and try again.");
        } finally { finish(connection); }
    }
    void finish(HttpsURLConnection connection) {
        if (active == connection) active = null;
        connection.disconnect();
    }
    static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096];
            int count;
            while ((count = input.read(bytes)) != -1) {
                if (output.size() + count > 1048576) throw new IOException("OpenAI response was too large.");
                output.write(bytes, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    static final class Result {
        final int status;
        final String body;
        Result(int status, String body) { this.status = status; this.body = body; }
    }
}
