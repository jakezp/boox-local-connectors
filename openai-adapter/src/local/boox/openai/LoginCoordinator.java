package local.boox.openai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;

/** Login belongs to the application, not the lifetime of one setup screen. */
final class LoginCoordinator {
    interface Listener { void changed(State state); }
    static final class State {
        final boolean running, succeeded;
        final CodexOAuth.DeviceCode code;
        final String message;
        State(boolean running, boolean succeeded, CodexOAuth.DeviceCode code, String message) {
            this.running = running; this.succeeded = succeeded; this.code = code; this.message = message;
        }
    }
    private static LoginCoordinator instance;
    static synchronized LoginCoordinator get(Context context) {
        if (instance == null) instance = new LoginCoordinator(context.getApplicationContext());
        return instance;
    }
    private final OAuthVault vault;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WeakReference<Listener> listener = new WeakReference<>(null);
    private State state = new State(false, false, null, "");
    private OpenAIHttp http;
    private Future<?> task;
    private long revision = -1;
    private int generation;

    private LoginCoordinator(Context context) { vault = new OAuthVault(context); }
    State state() { return state; }
    void attach(Listener listener) { this.listener = new WeakReference<>(listener); listener.changed(state); }
    void detach(Listener listener) { if (this.listener.get() == listener) this.listener.clear(); }
    void resume() {
        if (state.running || state.succeeded) return;
        try {
            OAuthVault.PendingLogin saved = vault.pending();
            if (saved != null) run(saved.revision, saved.code);
        } catch (Exception error) { showError(error); }
    }
    void start() throws Exception {
        cancel();
        run(vault.beginLogin(), null);
    }
    void cancel() throws Exception {
        generation++;
        if (http != null) http.cancel();
        if (task != null) task.cancel(true);
        if (revision >= 0) vault.cancelLogin(revision);
        revision = -1; http = null;
        publish(new State(false, false, null, ""));
    }
    private void run(long revision, CodexOAuth.DeviceCode saved) {
        this.revision = revision;
        int current = ++generation;
        OpenAIHttp exchange = new OpenAIHttp();
        http = exchange;
        publish(waiting(saved));
        task = worker.submit(() -> {
            try {
                CodexOAuth auth = new CodexOAuth(exchange);
                CodexOAuth.DeviceCode code = saved;
                if (code == null) {
                    code = auth.start();
                    vault.savePending(revision, code);
                }
                final CodexOAuth.DeviceCode available = code;
                post(current, () -> publish(waiting(available)));
                OAuthVault.Credentials credentials = auth.await(code);
                post(current, () -> {
                    try {
                        vault.saveLogin(revision, credentials);
                        this.revision = -1; http = null;
                        publish(new State(false, true, null, "ChatGPT sign-in completed."));
                    } catch (Exception error) { showError(error); }
                });
            } catch (Exception error) { post(current, () -> showError(error)); }
        });
    }
    private State waiting(CodexOAuth.DeviceCode code) {
        return new State(true, false, code, code == null ? "Requesting a ChatGPT sign-in code…"
            : "Copy the code, then open OpenAI sign-in and paste it. The same code stays here when you return. If asked, enable device-code login in ChatGPT security settings.");
    }
    private void post(int expected, Runnable action) {
        main.post(() -> { if (generation == expected) action.run(); });
    }
    private void showError(Exception error) {
        try { cancel(); } catch (Exception ignored) {}
        publish(new State(false, false, null, error instanceof IOException ? error.getMessage()
            : "Could not complete ChatGPT sign-in. Start sign-in again."));
    }
    private void publish(State next) {
        state = next;
        Listener target = listener.get();
        if (target != null) target.changed(next);
    }
}
