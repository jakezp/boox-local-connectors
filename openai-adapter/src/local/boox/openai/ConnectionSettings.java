package local.boox.openai;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import org.json.JSONObject;

/** Mode and model are captured for each request; there is no billing fallback. */
final class ConnectionSettings {
    static final String API = "api", CHATGPT = "chatgpt";
    private final Context context;
    private final SharedPreferences prefs;
    ConnectionSettings(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("connection_settings", Context.MODE_PRIVATE);
    }
    String mode() { return prefs.getString("mode", API); }
    String configuredMode() { return prefs.getString("setup_mode", mode()); }
    void configure(String mode) throws IOException {
        if (!API.equals(mode) && !CHATGPT.equals(mode)) throw new IOException("Unknown connection mode.");
        commit(prefs.edit().putString("setup_mode", mode));
    }
    String model(String mode) { return CHATGPT.equals(mode) ? prefs.getString("chatgpt_model", "") : new KeyVault(context).model(); }
    static boolean validModel(String model) { return model != null && model.matches("[A-Za-z0-9._:-]{1,100}"); }
    void saveChatModel(String model) throws IOException {
        if (!validModel(model)) throw new IOException("Choose a valid ChatGPT model first.");
        commit(prefs.edit().putString("chatgpt_model", model));
    }
    void verified(String model, OAuthVault.Session session) throws Exception {
        new OAuthVault(context).requireCurrent(session);
        if (!validModel(model)) throw new IOException("Choose a valid ChatGPT model first.");
        commit(prefs.edit().putString("verified_model", model).putString("verified_account", session.credentials.account)
            .putString("chatgpt_model", model));
    }
    void activate(String mode) throws Exception {
        if (CHATGPT.equals(mode)) requireVerified(snapshot(mode, model(mode)));
        else if (!API.equals(mode)) throw new IOException("Unknown connection mode.");
        else if (!new KeyVault(context).hasKey()) throw new IOException("Save your API key first.");
        commit(prefs.edit().putString("mode", mode));
    }
    Snapshot snapshot() throws Exception {
        String selected = mode();
        Snapshot snapshot = snapshot(selected, model(selected));
        if (CHATGPT.equals(selected)) requireVerified(snapshot);
        return snapshot;
    }
    private void requireVerified(Snapshot snapshot) throws Exception {
        new OAuthVault(context).requireCurrent(snapshot.session);
        if (!snapshot.model.equals(prefs.getString("verified_model", "")) ||
            !snapshot.session.credentials.account.equals(prefs.getString("verified_account", "")))
            throw new IOException("Test this ChatGPT account and model in BOOX OpenAI Setup before using it.");
    }
    Snapshot snapshot(String mode, String model) throws Exception {
        if (!API.equals(mode) && !CHATGPT.equals(mode)) throw new IOException("Select a connection in BOOX OpenAI Setup.");
        if (!validModel(model)) throw new IOException("Choose and test a model in BOOX OpenAI Setup first.");
        return new Snapshot(context, mode, model);
    }
    String label() { return CHATGPT.equals(mode()) ? "ChatGPT subscription" : "OpenAI API"; }
    String subtitle() { return CHATGPT.equals(mode()) ? "ChatGPT subscription · Codex" : "OpenAI API · billed separately"; }
    static final class Snapshot {
        final String mode, model;
        private final Context context;
        private final OAuthVault.Session session;
        Snapshot(Context context, String mode, String model) throws Exception {
            this.context = context; this.mode = mode; this.model = model;
            session = CHATGPT.equals(mode) ? new OAuthVault(context).session() : null;
        }
        String label() { return CHATGPT.equals(mode) ? "ChatGPT" : "OpenAI"; }
        ReplyClient client() throws Exception {
            if (CHATGPT.equals(mode)) return new CodexResponsesClient(context, session);
            return new ReplyClient() {
                private final ResponsesClient api = new ResponsesClient();
                @Override public String complete(JSONObject request) throws Exception {
                    return api.complete(new KeyVault(context).read(), request);
                }
                @Override public void cancel() { api.cancel(); }
                @Override public boolean isCancelled() { return api.isCancelled(); }
            };
        }
    }
    private static void commit(SharedPreferences.Editor edit) throws IOException {
        if (!edit.commit()) throw new IOException("Connection settings could not be saved.");
    }
}
