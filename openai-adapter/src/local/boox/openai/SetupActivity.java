package local.boox.openai;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SetupActivity extends Activity {
    private KeyVault apiVault;
    private OAuthVault oauthVault;
    private ConnectionSettings settings;
    private Spinner mode, chatModel;
    private EditText apiKey, apiModel;
    private LinearLayout apiPanel, chatPanel;
    private TextView status, activeLabel, accountLabel, codeLabel, modelHelp;
    private Button openLogin, copyCode, cancel;
    private final List<View> controls = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> task;
    private ReplyClient replyClient;
    private int operation;
    private LoginCoordinator login;
    private LoginCoordinator.State handledLogin;
    private final LoginCoordinator.Listener loginListener = this::showLogin;
    private final List<String> chatModels = new ArrayList<>();
    private String selectedChatModel;
    private boolean modelsRequested, started;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        apiVault = new KeyVault(this);
        oauthVault = new OAuthVault(this);
        settings = new ConnectionSettings(this);
        selectedChatModel = state == null ? settings.model(ConnectionSettings.CHATGPT)
            : state.getString("selected_chat_model", settings.model(ConnectionSettings.CHATGPT));
        login = LoginCoordinator.get(this);
        LinearLayout layout = column();
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.addView(layout); setContentView(scroll);
        label(layout, "BOOX OpenAI Setup", 26);
        activeLabel = label(layout, "", 17);
        label(layout, "Choose a connection to configure", 18);
        mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"OpenAI API", "ChatGPT subscription"}));
        layout.addView(mode); controls.add(mode);
        apiPanel = column(); layout.addView(apiPanel);
        label(apiPanel, "API key", 17);
        apiKey = edit(apiPanel, apiVault.hasKey() ? "Saved — leave blank to keep" : "Enter your API key");
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        apiKey.setSaveEnabled(false);
        label(apiPanel, "API model", 17);
        apiModel = edit(apiPanel, "Model ID"); apiModel.setText(apiVault.model());
        label(apiPanel, "API usage is billed to your OpenAI API account.", 15);
        button(apiPanel, "Remove API key", () -> {
            apiVault.clear(); apiKey.setText(""); apiKey.setHint("Enter your API key");
            message("API key removed. ChatGPT credentials are unchanged.");
        });
        chatPanel = column(); layout.addView(chatPanel);
        accountLabel = label(chatPanel, "", 17);
        button(chatPanel, "Sign in with ChatGPT", this::startLogin);
        label(chatPanel, "Uses Codex subscription access. Complete sign-in on OpenAI's page; it may identify the public Codex client.", 15);
        codeLabel = label(chatPanel, "", 22); codeLabel.setTextIsSelectable(true); codeLabel.setVisibility(View.GONE);
        copyCode = button(chatPanel, "Copy code", () -> {
            CodexOAuth.DeviceCode code = login.state().code;
            if (code == null) throw new IOException("Start ChatGPT sign-in to get a code first.");
            ClipData clip = ClipData.newPlainText("ChatGPT sign-in code", code.code);
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
            message("Code copied. Open OpenAI sign-in and paste it into the code field.");
        });
        controls.remove(copyCode); copyCode.setVisibility(View.GONE);
        openLogin = button(chatPanel, "Open OpenAI sign-in", () ->
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CodexOAuth.SIGN_IN_PAGE))));
        controls.remove(openLogin); openLogin.setVisibility(View.GONE);
        label(chatPanel, "ChatGPT model", 17);
        chatModel = new Spinner(this, Spinner.MODE_DROPDOWN);
        chatModel.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Sign in to load available models"}));
        chatPanel.addView(chatModel); controls.add(chatModel);
        chatModel.setEnabled(false);
        chatModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0 && position <= chatModels.size()) selectedChatModel = chatModels.get(position - 1);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        modelHelp = label(chatPanel, "Choose from the models available to your signed-in ChatGPT account.", 15);
        button(chatPanel, "Refresh available models", this::loadModels);
        button(chatPanel, "Sign out of ChatGPT", () -> {
            login.cancel(); oauthVault.clear(); refreshLabels();
            message("Signed out of ChatGPT. Your saved API key and conversation history are unchanged.");
        });
        label(chatPanel, "ChatGPT mode never switches automatically to paid API usage.", 15);
        button(layout, "Test selected connection", this::testConnection);
        button(layout, "Use selected connection", () -> {
            String selected = selectedMode();
            if (ConnectionSettings.API.equals(selected)) saveApi();
            else settings.saveChatModel(selectedChatModel());
            settings.activate(selected); refreshLabels();
            message("Connection selected. Close and reopen AI Assistant or the NeoReader AI panel.");
        });
        cancel = button(layout, "Cancel", () -> cancelOperation("Canceled."));
        controls.remove(cancel); cancel.setVisibility(View.GONE);
        status = label(layout, "Connection tests send only a short check; no books or notes.", 16);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                apiPanel.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                chatPanel.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
                try { settings.configure(position == 0 ? ConnectionSettings.API : ConnectionSettings.CHATGPT); }
                catch (Exception error) { failure(operation, error); }
                maybeLoadModels();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        mode.setSelection(ConnectionSettings.CHATGPT.equals(settings.configuredMode()) ? 1 : 0);
        apiPanel.setVisibility(mode.getSelectedItemPosition() == 0 ? View.VISIBLE : View.GONE);
        chatPanel.setVisibility(mode.getSelectedItemPosition() == 1 ? View.VISIBLE : View.GONE);
        refreshLabels();
    }
    private String selectedMode() { return mode.getSelectedItemPosition() == 1 ? ConnectionSettings.CHATGPT : ConnectionSettings.API; }
    private String selectedChatModel() throws IOException {
        int index = chatModel.getSelectedItemPosition() - 1;
        if (index < 0 || index >= chatModels.size()) throw new IOException("Choose an available ChatGPT model from the dropdown first.");
        return chatModels.get(index);
    }
    private void showModels(List<String> models) {
        chatModels.clear(); chatModels.addAll(models);
        List<String> labels = new ArrayList<>();
        labels.add("Choose a model");
        for (String model : models) labels.add("gpt-5.6-luna".equals(model) ? model + " · quick lookups" : model);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels) {
            @Override public boolean isEnabled(int position) { return position > 0; }
            @Override public boolean areAllItemsEnabled() { return false; }
        };
        chatModel.setAdapter(adapter);
        chatModel.setSelection(Math.max(0, models.indexOf(selectedChatModel) + 1));
        modelHelp.setText(models.contains("gpt-5.6-luna")
            ? "For basic lookups, GPT-5.6 Luna is a lighter choice. Model choice affects your ChatGPT usage allowance."
            : "Choose a model for your task. Model choice affects your ChatGPT usage allowance.");
    }
    private void saveApi() throws Exception {
        String entered = apiKey.getText().toString().trim(), model = apiModel.getText().toString().trim();
        if (!ConnectionSettings.validModel(model)) throw new IOException("Enter a valid API model ID.");
        if ((!entered.isEmpty() && !entered.matches("[!-~]{10,1024}")) || (entered.isEmpty() && !apiVault.hasKey()))
            throw new IOException("Enter your API key first.");
        apiVault.save(entered, model);
        apiKey.setText(""); apiKey.setHint("Saved — leave blank to keep");
    }
    private void startLogin() throws Exception {
        settings.configure(ConnectionSettings.CHATGPT);
        login.start();
    }
    private void showLogin(LoginCoordinator.State state) {
        if (state.running) {
            mode.setSelection(1);
            busy(true);
        }
        boolean hasCode = state.code != null;
        codeLabel.setText(hasCode ? "Your sign-in code: " + state.code.code : "");
        codeLabel.setVisibility(hasCode ? View.VISIBLE : View.GONE);
        copyCode.setVisibility(hasCode ? View.VISIBLE : View.GONE);
        openLogin.setVisibility(hasCode ? View.VISIBLE : View.GONE);
        if (!state.message.isEmpty()) message(state.message);
        if (!state.running && replyClient == null) busy(false);
        refreshLabels();
        if (state.succeeded && handledLogin != state) {
            handledLogin = state;
            try { loadModels(); } catch (Exception error) { failure(operation, error); }
        }
    }
    private void loadModels() throws Exception {
        final CodexResponsesClient client = new CodexResponsesClient(this);
        modelsRequested = true;
        replyClient = client;
        final int current = ++operation;
        busy(true); message("Loading models available to your ChatGPT account…");
        task = worker.submit(() -> {
            try {
                List<String> models = client.models();
                ui(current, () -> {
                    showModels(models);
                    replyClient = null; busy(false); refreshLabels();
                    message("Models loaded. Test the selected connection, then choose Use selected connection.");
                });
            } catch (Exception error) { failure(current, error); }
        });
    }
    private void testConnection() throws Exception {
        final String selected = selectedMode();
        if (ConnectionSettings.API.equals(selected)) saveApi();
        final String model = ConnectionSettings.CHATGPT.equals(selected)
            ? selectedChatModel() : apiVault.model();
        final ConnectionSettings.Snapshot connection = settings.snapshot(selected, model);
        final OAuthVault.Session session = ConnectionSettings.CHATGPT.equals(selected) ? oauthVault.session() : null;
        final ReplyClient client = connection.client();
        replyClient = client;
        final int current = ++operation;
        busy(true); message("Testing " + (session == null ? "OpenAI API" : "ChatGPT subscription") + "…");
        task = worker.submit(() -> {
            try {
                JSONObject request = new JSONObject().put("model", model).put("store", false).put("max_output_tokens", 128)
                    .put("input", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "Reply with exactly: BOOX connection working.")));
                String answer = client.complete(request);
                ui(current, () -> {
                    try {
                        if (session != null) settings.verified(model, session);
                        replyClient = null; busy(false); refreshLabels();
                        message("Connection test passed.\n\n" + answer + "\n\nChoose Use selected connection to activate this mode.");
                    } catch (Exception error) { failure(current, error); }
                });
            } catch (Exception error) { failure(current, error); }
        });
    }
    private void cancelOperation(String text) throws Exception {
        if (login.state().running) login.cancel();
        stopLocalWork();
        busy(false); refreshLabels(); message(text);
    }
    private void failure(int current, Exception error) {
        ui(current, () -> {
            String text = error instanceof IOException ? error.getMessage() : "Could not complete this action. Check the connection and try again.";
            try { cancelOperation(text); } catch (Exception ignored) { busy(false); message(text); }
        });
    }
    private void refreshLabels() {
        activeLabel.setText("Active: " + settings.label());
        String text = "Not signed in to ChatGPT.";
        try {
            if (oauthVault.hasSession()) {
                String plan = oauthVault.session().credentials.plan;
                text = "ChatGPT signed in" + (plan.isEmpty() ? "." : " · " + plan);
            }
        } catch (Exception ignored) { text = "ChatGPT sign-in could not be read. Sign in again."; }
        accountLabel.setText(text);
    }
    private void ui(int current, Runnable action) {
        runOnUiThread(() -> { if (!isDestroyed() && operation == current) action.run(); });
    }
    private void busy(boolean value) {
        for (View control : controls) control.setEnabled(!value);
        chatModel.setEnabled(!value && !chatModels.isEmpty());
        cancel.setVisibility(value ? View.VISIBLE : View.GONE);
    }
    private void message(String value) { status.setText(value); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }
    private EditText edit(LinearLayout layout, String hint) {
        EditText view = new EditText(this); view.setSingleLine(true); view.setHint(hint);
        layout.addView(view); controls.add(view); return view;
    }
    private TextView label(LinearLayout layout, String value, int size) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size);
        text.setPadding(0, 10, 0, 10); layout.addView(text); return text;
    }
    private interface Action { void run() throws Exception; }
    private Button button(LinearLayout layout, String value, Action action) {
        Button button = new Button(this); button.setText(value); layout.addView(button); controls.add(button);
        button.setOnClickListener(view -> { try { action.run(); } catch (Exception error) { failure(operation, error); } });
        return button;
    }
    @Override protected void onStart() {
        super.onStart();
        started = true;
        login.attach(loginListener);
        login.resume();
        maybeLoadModels();
    }
    private void maybeLoadModels() {
        if (started && !modelsRequested && !login.state().running && replyClient == null && oauthVault.hasSession() &&
            ConnectionSettings.CHATGPT.equals(selectedMode())) {
            try { loadModels(); } catch (Exception error) { failure(operation, error); }
        }
    }
    @Override protected void onStop() {
        started = false;
        login.detach(loginListener);
        super.onStop();
    }
    private void stopLocalWork() {
        operation++;
        if (replyClient != null) replyClient.cancel();
        if (task != null) task.cancel(true);
        replyClient = null;
    }
    @Override protected void onDestroy() {
        // Browser navigation/recreation must not cancel application-owned sign-in.
        stopLocalWork(); worker.shutdownNow(); super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("selected_chat_model", selectedChatModel);
        super.onSaveInstanceState(state);
    }
}
