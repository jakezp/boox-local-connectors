package local.boox.openai;

import android.os.SystemClock;
import android.util.Base64;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Public Codex device authorization protocol; the user signs in on OpenAI's page. */
final class CodexOAuth {
    static final String SIGN_IN_PAGE = "https://auth.openai.com/codex/device";
    private static final String AUTH = "https://auth.openai.com";
    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private final OpenAIHttp http;
    CodexOAuth(OpenAIHttp http) { this.http = http; }

    static final class DeviceCode {
        final String id, code;
        final long deadline;
        final long expiresAt;
        final int interval;
        DeviceCode(String id, String code, int interval) {
            this(id, code, interval, SystemClock.elapsedRealtime() + 15 * 60 * 1000,
                System.currentTimeMillis() + 15 * 60 * 1000);
        }
        DeviceCode(String id, String code, int interval, long deadline, long expiresAt) {
            this.id = id; this.code = code; this.interval = interval;
            this.deadline = deadline; this.expiresAt = expiresAt;
        }
    }
    DeviceCode start() throws Exception {
        OpenAIHttp.Result response = http.exchange(AUTH + "/api/accounts/deviceauth/usercode",
            "application/json", new JSONObject().put("client_id", CLIENT_ID).toString());
        if (response.status == 403 || response.status == 404)
            throw new IOException("Enable device-code login in your ChatGPT security settings, then try again.");
        if (response.status != 200) throw authError(response.status, false);
        JSONObject data = json(response.body);
        String id = data.optString("device_auth_id"), code = data.optString("user_code");
        int interval = data.optInt("interval", -1);
        if (id.isEmpty() || id.length() > 4096 || !code.matches("[A-Za-z0-9-]{4,32}") || interval < 0 || interval > 60)
            throw new IOException("OpenAI returned an incomplete sign-in code. Try signing in again.");
        return new DeviceCode(id, code, Math.max(1, interval));
    }
    OAuthVault.Credentials await(DeviceCode device) throws Exception {
        int interval = device.interval;
        while (SystemClock.elapsedRealtime() < device.deadline) {
            http.check();
            OpenAIHttp.Result response = http.exchange(AUTH + "/api/accounts/deviceauth/token", "application/json",
                new JSONObject().put("device_auth_id", device.id).put("user_code", device.code).toString());
            if (response.status == 200) {
                JSONObject data = json(response.body);
                String code = data.optString("authorization_code"), verifier = data.optString("code_verifier");
                if (code.isEmpty() || verifier.isEmpty() || code.length() > 8192 || verifier.length() > 1024)
                    throw new IOException("OpenAI returned an incomplete authorization. Sign in again.");
                OpenAIHttp.Result token = http.exchange(AUTH + "/oauth/token", "application/x-www-form-urlencoded",
                    form("grant_type", "authorization_code", "client_id", CLIENT_ID, "code", code,
                        "code_verifier", verifier, "redirect_uri", AUTH + "/deviceauth/callback"));
                if (token.status != 200) throw authError(token.status, false);
                return credentials(json(token.body), null);
            }
            String error = errorCode(response.body);
            if ("expired_token".equals(error) || "deviceauth_expired".equals(error))
                throw new IOException("The sign-in code expired. Start sign-in again.");
            if ("access_denied".equals(error))
                throw new IOException("ChatGPT sign-in was declined.");
            if ("slow_down".equals(error) || response.status == 429) interval = Math.min(60, interval + 5);
            else if (response.status != 403 && response.status != 404 &&
                !"authorization_pending".equals(error) && !"deviceauth_authorization_pending".equals(error))
                throw authError(response.status, false);
            waitForPoll(interval, device.deadline);
        }
        throw new IOException("The sign-in code expired. Start sign-in again.");
    }
    OAuthVault.Credentials refresh(OAuthVault.Credentials old) throws Exception {
        OpenAIHttp.Result response = http.exchange(AUTH + "/oauth/token", "application/x-www-form-urlencoded",
            form("grant_type", "refresh_token", "refresh_token", old.refresh, "client_id", CLIENT_ID));
        if (response.status != 200) throw authError(response.status, true);
        return credentials(json(response.body), old.refresh);
    }
    static OAuthVault.Credentials credentials(JSONObject data, String previousRefresh) throws Exception {
        String access = data.optString("access_token"), refresh = data.optString("refresh_token", previousRefresh);
        long seconds = data.optLong("expires_in");
        if (!access.matches("[!-~]{10,32768}") || refresh == null || !refresh.matches("[!-~]{10,16384}") ||
            seconds <= 0 || seconds > 366L * 24 * 3600)
            throw new IOException("OpenAI returned incomplete sign-in credentials. Sign in again.");
        String[] parts = access.split("\\.");
        if (parts.length != 3) throw new IOException("OpenAI returned an unrecognized sign-in token.");
        JSONObject claims;
        try { claims = new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IOException("OpenAI returned an unrecognized sign-in token."); }
        JSONObject auth = claims.optJSONObject("https://api.openai.com/auth");
        String account = auth == null ? "" : auth.optString("chatgpt_account_id");
        String plan = auth == null ? "" : auth.optString("chatgpt_plan_type");
        if (!account.matches("[A-Za-z0-9_-]{1,200}"))
            throw new IOException("This login did not include a ChatGPT account. Sign in again.");
        if (!plan.matches("[A-Za-z0-9_-]{0,60}")) plan = "";
        return new OAuthVault.Credentials(access, refresh, account, plan, System.currentTimeMillis() + seconds * 1000);
    }
    private void waitForPoll(int seconds, long deadline) throws IOException {
        long until = Math.min(deadline, SystemClock.elapsedRealtime() + seconds * 1000L);
        while (SystemClock.elapsedRealtime() < until) {
            http.check();
            try { Thread.sleep(Math.min(200, Math.max(1, until - SystemClock.elapsedRealtime()))); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Sign-in canceled."); }
        }
    }
    private static String form(String... fields) throws Exception {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < fields.length; i += 2) {
            if (i != 0) result.append('&');
            result.append(URLEncoder.encode(fields[i], "UTF-8")).append('=')
                .append(URLEncoder.encode(fields[i + 1], "UTF-8"));
        }
        return result.toString();
    }
    private static JSONObject json(String value) throws IOException {
        try { return new JSONObject(value); }
        catch (Exception error) { throw new IOException("OpenAI returned an unexpected sign-in response. Try again."); }
    }
    private static String errorCode(String body) {
        try {
            JSONObject data = new JSONObject(body);
            JSONObject error = data.optJSONObject("error");
            return error == null ? data.optString("error") : error.optString("code");
        } catch (Exception ignored) { return ""; }
    }
    private static IOException authError(int status, boolean refresh) {
        if (status == 429) return new IOException("OpenAI sign-in is temporarily rate limited. Try again later.");
        if (refresh && (status == 400 || status == 401 || status == 403))
            return new IOException("ChatGPT sign-in expired or was revoked. Sign in again in BOOX OpenAI Setup.");
        if (status >= 500) return new IOException("OpenAI sign-in is temporarily unavailable. Try again later.");
        return new IOException("OpenAI could not complete sign-in (HTTP " + status + "). Try signing in again.");
    }
}
