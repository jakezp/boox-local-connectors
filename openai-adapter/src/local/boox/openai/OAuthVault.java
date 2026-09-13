package local.boox.openai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Subscription credentials never leave this app's private process/storage. */
final class OAuthVault {
    private static final Object LOCK = new Object();
    private static final Object REFRESH_LOCK = new Object();
    private final SharedPreferences prefs;
    private final String alias;
    OAuthVault(Context context) {
        prefs = context.getSharedPreferences("chatgpt_oauth", Context.MODE_PRIVATE);
        alias = "boox-chatgpt-oauth-v1-" + context.getPackageName();
    }

    static final class Credentials {
        final String access, refresh, account, plan;
        final long expires;
        Credentials(String access, String refresh, String account, String plan, long expires) {
            this.access = access; this.refresh = refresh; this.account = account;
            this.plan = plan; this.expires = expires;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("access", access).put("refresh", refresh)
                .put("account", account).put("plan", plan).put("expires", expires);
        }
    }
    static final class Session {
        final long revision;
        final Credentials credentials;
        Session(long revision, Credentials credentials) { this.revision = revision; this.credentials = credentials; }
    }
    static final class PendingLogin {
        final long revision;
        final CodexOAuth.DeviceCode code;
        PendingLogin(long revision, CodexOAuth.DeviceCode code) { this.revision = revision; this.code = code; }
    }
    boolean hasSession() { synchronized (LOCK) { return prefs.contains("ciphertext"); } }
    long beginLogin() throws Exception {
        synchronized (LOCK) {
            long revision = prefs.getLong("revision", 0) + 1;
            commit(removePending(prefs.edit().putLong("revision", revision)));
            return revision;
        }
    }
    void cancelLogin(long revision) throws Exception {
        synchronized (LOCK) {
            if (prefs.getLong("revision", 0) == revision)
                commit(removePending(prefs.edit().putLong("revision", revision + 1)));
        }
    }
    void saveLogin(long revision, Credentials credentials) throws Exception {
        synchronized (LOCK) {
            checkRevision(revision);
            // Requests may have captured the previous account while login was pending.
            save(credentials, revision + 1, true);
        }
    }
    void savePending(long revision, CodexOAuth.DeviceCode code) throws Exception {
        synchronized (LOCK) {
            checkRevision(revision);
            JSONObject data = new JSONObject().put("revision", revision).put("id", code.id)
                .put("code", code.code).put("interval", code.interval).put("deadline", code.deadline)
                .put("expiresAt", code.expiresAt);
            commit(encrypted("pending_", data));
        }
    }
    PendingLogin pending() throws Exception {
        synchronized (LOCK) {
            if (!prefs.contains("pending_ciphertext")) return null;
            JSONObject data = decrypt("pending_");
            long revision = data.getLong("revision");
            checkRevision(revision);
            long now = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
            long remaining = Math.min(data.getLong("deadline") - now, data.getLong("expiresAt") - wall);
            if (remaining <= 0 || remaining > 15 * 60 * 1000) {
                cancelLogin(revision);
                throw new IOException("The previous sign-in code expired. Tap Sign in with ChatGPT for a new code.");
            }
            CodexOAuth.DeviceCode code = new CodexOAuth.DeviceCode(data.getString("id"), data.getString("code"),
                data.getInt("interval"), now + remaining, wall + remaining);
            return new PendingLogin(revision, code);
        }
    }
    Session session() throws Exception {
        synchronized (LOCK) {
            if (!prefs.contains("ciphertext")) throw new IOException("Sign in with ChatGPT in BOOX OpenAI Setup first.");
            JSONObject data = decrypt("");
            Credentials credentials = new Credentials(data.getString("access"), data.getString("refresh"),
                data.getString("account"), data.optString("plan"), data.getLong("expires"));
            return new Session(prefs.getLong("revision", 0), credentials);
        }
    }
    boolean isCurrent(Session expected) {
        synchronized (LOCK) {
            return expected != null && prefs.contains("ciphertext") &&
                prefs.getLong("revision", 0) == expected.revision;
        }
    }
    void requireCurrent(Session expected) throws IOException {
        if (!isCurrent(expected)) throw new IOException("ChatGPT sign-in changed. Send your question again.");
    }
    Session fresh(Session expected, OpenAIHttp http, boolean force) throws Exception {
        // Only one refresh token consumer, without blocking logout on network IO.
        synchronized (REFRESH_LOCK) {
            http.check();
            Session current;
            synchronized (LOCK) {
                requireCurrent(expected);
                current = session();
                if (!current.credentials.account.equals(expected.credentials.account))
                    throw new IOException("ChatGPT account changed. Send your question again.");
            }
            if (!current.credentials.access.equals(expected.credentials.access)) return current;
            if (!force && current.credentials.expires > System.currentTimeMillis() + 60000) return current;
            Credentials updated = new CodexOAuth(http).refresh(current.credentials);
            http.check();
            synchronized (LOCK) {
                checkRevision(expected.revision);
                if (!updated.account.equals(current.credentials.account))
                    throw new IOException("ChatGPT account changed during refresh. Sign in again.");
                save(updated, expected.revision, false);
                return new Session(expected.revision, updated);
            }
        }
    }
    void clear() throws Exception {
        synchronized (LOCK) {
            long next = prefs.getLong("revision", 0) + 1;
            commit(prefs.edit().clear().putLong("revision", next));
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            store.deleteEntry(alias);
        }
    }
    private void checkRevision(long revision) throws IOException {
        if (prefs.getLong("revision", 0) != revision)
            throw new IOException("Sign-in was canceled or replaced. Start sign-in again.");
    }
    private void save(Credentials credentials, long revision, boolean completeLogin) throws Exception {
        SharedPreferences.Editor edit = encrypted("", credentials.json()).putLong("revision", revision);
        commit(completeLogin ? removePending(edit) : edit);
    }
    private SharedPreferences.Editor encrypted(String prefix, JSONObject data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.doFinal(
            data.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        return prefs.edit().putString(prefix + "iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString(prefix + "ciphertext", encrypted);
    }
    private JSONObject decrypt(String prefix) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
            Base64.decode(prefs.getString(prefix + "iv", ""), Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(
            Base64.decode(prefs.getString(prefix + "ciphertext", ""), Base64.NO_WRAP)), StandardCharsets.UTF_8));
    }
    private static SharedPreferences.Editor removePending(SharedPreferences.Editor edit) {
        return edit.remove("pending_iv").remove("pending_ciphertext");
    }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(alias, null);
    }
    private static void commit(SharedPreferences.Editor edit) throws IOException {
        if (!edit.commit()) throw new IOException("ChatGPT sign-in could not be saved.");
    }
}
