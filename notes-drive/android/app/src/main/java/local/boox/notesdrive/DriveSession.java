package local.boox.notesdrive;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Owns ongoing connection work across activity/browser transitions. */
public final class DriveSession extends Application {
    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Runnable listener;
    private String token;
    private String accountId = "";
    String accountLabel = "", folderId = "";
    String status = "Connect Google Drive to set up a sync directory.";
    String revisionStatus = "Refresh to check the notebooks stored in your Drive sync directory.";
    RevisionCatalog catalog;
    static final String VALIDATION_NOTEBOOK = "boox-validation-notebook-v1";
    boolean busy;
    private boolean activeSyncScheduled;
    private Runnable deferredConflict;
    PendingIntent resolution;
    final List<DriveClient.Folder> folders = new ArrayList<>();

    @Override public void onCreate() {
        super.onCreate();
        if (automaticEnabled()) status = "Automatic publishing is enabled. Checking the saved Google connection…";
        AutoSyncJob.schedule(this, true);
        AutoSyncJob.schedule(this, false);
        requestAutomaticSync();
        getSystemService(android.net.ConnectivityManager.class).registerDefaultNetworkCallback(
            new android.net.ConnectivityManager.NetworkCallback() {
                private boolean ready;
                @Override public void onCapabilitiesChanged(android.net.Network network,
                        android.net.NetworkCapabilities capabilities) {
                    boolean validated = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                        capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    if (validated && !ready) requestAutomaticSync();
                    ready = validated;
                }
                @Override public void onLost(android.net.Network network) { ready = false; }
            });
    }

    void requestAutomaticSync() {
        main.post(() -> {
            if (!automaticEnabled() || activeSyncScheduled) return;
            activeSyncScheduled = true;
            main.postDelayed(() -> {
                activeSyncScheduled = false;
                if (busy || deferredConflict != null) { requestAutomaticSync(); return; }
                // Begin while the process is already alive; persisted jobs cover interruption/sleep.
                automaticSync(retry -> { if (retry) AutoSyncJob.schedule(this, false); });
            }, 1500);
        });
    }

    boolean automaticEnabled() { return preferences().getBoolean("automatic", false); }
    boolean incomingEnabled() { return preferences().getBoolean("automaticIncoming", false); }
    android.os.Bundle nativeSyncSettings(android.os.Bundle request) throws Exception {
        // Binder calls may arrive off-main; keep configuration changes serialized with normal app actions.
        java.util.concurrent.FutureTask<android.os.Bundle> task = new java.util.concurrent.FutureTask<>(() -> {
            android.os.Bundle result = new android.os.Bundle();
            if (request != null && request.containsKey("enabled")) {
                boolean enable = request.getBoolean("enabled");
                if (busy) result.putString("error", "Sync is busy. Try the switch again when the current check finishes.");
                else if (enable && (!connected() || folderId.isEmpty()))
                    result.putBoolean("setupRequired", true);
                else {
                    if (enable) {
                        setAutomatic(true);
                        setIncoming(true);
                    } else {
                        setIncoming(false);
                        setAutomatic(false);
                    }
                }
            }
            result.putBoolean("enabled", automaticEnabled() && incomingEnabled());
            return result;
        });
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else main.post(task);
        try { return task.get(3, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException error) {
            task.cancel(false);
            throw new IOException("Drive settings did not respond. Open setup to check their state.", error);
        }
    }
    void setIncoming(boolean enabled) {
        if (busy || !automaticEnabled()) return;
        preferences().edit().putBoolean("automaticIncoming", enabled).commit();
        if (enabled) requestAutomaticSync();
        changed();
    }
    void retryIncoming() {
        preferences().edit().putString("incomingRetry", java.util.UUID.randomUUID().toString()).commit();
        requestAutomaticSync();
    }
    String automaticStatus() {
        return preferences().getString("autoStatus", "Automatic publishing is off.") + "\n" +
            preferences().getString("nativeStatus", "Open Notes once to connect the native save adapter.");
    }
    void setAutomatic(boolean enabled) {
        if (busy || (enabled && (!connected() || folderId.isEmpty()))) return;
        boolean replaceOnyx = enabled || preferences().getBoolean("replaceOnyx", automaticEnabled());
        preferences().edit().putBoolean("automatic", enabled)
            .putBoolean("replaceOnyx", replaceOnyx)
            .putString("autoGeneration", java.util.UUID.randomUUID().toString())
            .putString("autoStatus", enabled ? "Automatic publishing enabled. Open Notes to scan the library." :
                "Automatic publishing paused. Saved uploads are retained.").commit();
        AutoSyncJob.schedule(this, true);
        AutoSyncJob.schedule(this, false);
        if (enabled) requestAutomaticSync();
        changed();
    }

    interface Completion { void finished(boolean retry); }
    void automaticSync(Completion completion) {
        if (!automaticEnabled()) { completion.finished(false); return; }
        if (busy || deferredConflict != null) { completion.finished(true); return; }
        busy = true;
        preferences().edit().putString("autoStatus", "Checking Google access for automatic sync…").apply();
        changed();
        String expectedAccount = preferences().getString("account", "");
        String selectedFolder = preferences().getString("folder", "");
        String generation = preferences().getString("autoGeneration", "");
        AuthorizationRequest request = AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(SCOPE))).build();
        Identity.getAuthorizationClient(this).authorize(request).addOnSuccessListener(result -> {
            if (result.hasResolution() || !hasDriveGrant(result)) {
                finishAutomatic("Google sign-in needs attention. Open Setup and reconnect.", false, completion);
                return;
            }
            String accessToken = result.getAccessToken();
            worker.execute(() -> {
                try {
                    DriveClient client = new DriveClient(accessToken);
                    JSONObject user = requireAccount(client, expectedAccount);
                    DriveClient.Folder folder = client.checkFolder(selectedFolder);
                    DriveClient.require(automaticEnabled() && generation.equals(
                        preferences().getString("autoGeneration", "")), "Automatic publishing settings changed");
                    String device = preferences().getString("device", "");
                    if (device.isEmpty()) {
                        device = "android-" + java.util.UUID.randomUUID();
                        DriveClient.require(preferences().edit().putString("device", device).commit(),
                            "Cannot persist sync device identity");
                    }
                    RevisionQueue outgoing = queue();
                    LibraryQueue library = new LibraryQueue(new java.io.File(getFilesDir(), "library"));
                    // Finish uncertain publications before assigning further local descendants.
                    RevisionDriveStore store = revisionStore(client, expectedAccount, selectedFolder);
                    RevisionQueue.Publisher publisher = (revision, bytes) -> {
                        preferences().edit().putString("autoStatus",
                            "Automatically uploading " + revision.notebook + "…").apply();
                        main.post(this::changed);
                        store.publish(revision, bytes);
                    };
                    int uploaded = outgoing.retry(expectedAccount, selectedFolder, publisher);
                    library.stage(expectedAccount, selectedFolder, device, outgoing);
                    uploaded += outgoing.retry(expectedAccount, selectedFolder, publisher);
                    RevisionCatalog resultCatalog = store.load();
                    saveCatalog(resultCatalog, expectedAccount, selectedFolder);
                    int more = library.stage(expectedAccount, selectedFolder, device, outgoing);
                    int count = uploaded;
                    main.post(() -> {
                        token = accessToken;
                        accountId = expectedAccount;
                        accountLabel = user.optString("emailAddress", user.optString("displayName", "Google Drive"));
                        folderId = selectedFolder;
                        if (folders.isEmpty()) folders.add(folder);
                        status = "Connected. Automatic notebook publishing is enabled.";
                        catalog = resultCatalog;
                        revisionStatus = catalogSummary(resultCatalog, more);
                        finishAutomatic("Last automatic check: " + java.text.DateFormat.getTimeInstance().format(new java.util.Date()) +
                            " · " + count + " revision(s) uploaded. Incoming revisions are staged.", more > 0, completion);
                    });
                } catch (Exception error) {
                    if (error instanceof DriveClient.Unauthorized)
                        Identity.getAuthorizationClient(this).clearToken(
                            ClearTokenRequest.builder().setToken(accessToken).build());
                    main.post(() -> finishAutomatic("Automatic sync will retry: " +
                        (error instanceof IOException ? error.getMessage() : error.getClass().getSimpleName()), true, completion));
                }
            });
        }).addOnFailureListener(error -> finishAutomatic(
            "Google access is temporarily unavailable. Saved notebooks remain queued.", true, completion));
    }

    private void finishAutomatic(String message, boolean retry, Completion completion) {
        preferences().edit().putString("autoStatus", message).apply();
        busy = false;
        changed();
        completion.finished(retry);
    }

    void attach(Runnable listener) { this.listener = listener; changed(); }
    void detach(Runnable listener) { if (this.listener == listener) this.listener = null; }
    boolean connected() { return token != null && !accountId.isEmpty(); }
    private SharedPreferences preferences() { return getSharedPreferences("drive", MODE_PRIVATE); }
    private void changed() { if (listener != null) listener.run(); }

    void connect() {
        if (busy) return;
        busy = true;
        token = null;
        catalog = null;
        revisionStatus = "Refresh revisions after connecting to verify this directory.";
        accountId = accountLabel = folderId = "";
        folders.clear();
        status = "Requesting Google Drive access…";
        changed();
        AuthorizationRequest request = AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(SCOPE))).build();
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener(this::authorized)
            .addOnFailureListener(this::authorizationFailed);
    }

    private void authorized(AuthorizationResult result) {
        if (result.hasResolution()) {
            resolution = result.getPendingIntent();
            if (resolution == null) {
                authorizationFailed(new IOException("Google did not provide an authorization screen"));
                return;
            }
            status = "Complete the Google account and permission screen.";
            changed();
            return;
        }
        accept(result);
    }

    void authorizationResult(Intent data) {
        try {
            if (data == null) throw new IOException("Google authorization was cancelled");
            accept(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data));
        } catch (Exception error) { authorizationFailed(error); }
    }

    void authorizationFailed(Exception error) {
        resolution = null;
        busy = false;
        token = null;
        if (error instanceof ApiException && ((ApiException) error).getStatusCode() == 10) {
            status = "Google registration is required. Register this app’s package and signing " +
                "certificate in Google Cloud, enable the Drive API, then reconnect.";
        } else if (error instanceof ApiException) {
            status = "Google authorization did not finish (code " +
                ((ApiException) error).getStatusCode() + "). You can reconnect.";
        } else status = "Google authorization did not finish. You can reconnect.";
        changed();
    }

    private void accept(AuthorizationResult result) {
        resolution = null;
        String accessToken = result.getAccessToken();
        if (!hasDriveGrant(result)) {
            authorizationFailed(new IOException("Drive access was not granted"));
            return;
        }
        // Google Play services manages renewal. No bearer token is persisted or logged.
        token = accessToken;
        status = "Checking the Drive account and available directories…";
        changed();
        DriveClient client = new DriveClient(accessToken);
        worker.execute(() -> {
            try {
                JSONObject user = client.account();
                List<DriveClient.Folder> available = client.folders();
                main.post(() -> {
                    accountId = user.optString("permissionId");
                    accountLabel = user.optString("emailAddress", user.optString("displayName", "Google Drive"));
                    folders.clear();
                    folders.addAll(available);
                    String saved = preferences().getString("account", "").equals(accountId) ?
                        preferences().getString("folder", "") : "";
                    folderId = "";
                    for (DriveClient.Folder folder : folders)
                        if (folder.id.equals(saved)) folderId = saved;
                    busy = false;
                    status = folders.isEmpty() ? "Connected. Create your sync directory below." :
                        (folderId.isEmpty() ? "Connected. Choose your sync directory." : "Connected. Ready to test.");
                    changed();
                });
            } catch (Exception error) { main.post(() -> failed(error)); }
        });
    }

    static boolean hasDriveGrant(AuthorizationResult result) {
        return result.getAccessToken() != null && !result.getAccessToken().isEmpty() &&
            result.getGrantedScopes() != null && result.getGrantedScopes().contains(SCOPE);
    }

    void selectFolder(String id) {
        if (busy || !connected() || id.equals(folderId)) return;
        if (automaticEnabled()) {
            status = "Pause automatic publishing before changing the sync directory.";
            changed();
            return;
        }
        for (DriveClient.Folder folder : folders) {
            if (folder.id.equals(id)) {
                folderId = id;
                catalog = null;
                revisionStatus = "Refresh revisions to verify this directory.";
                preferences().edit().putString("account", accountId).putString("folder", id).apply();
                status = "Directory selected. Ready to test.";
                changed();
                return;
            }
        }
    }

    void createFolder() {
        if (busy || !connected() || !folders.isEmpty()) return;
        busy = true;
        status = "Creating BOOX Notes Sync in Google Drive…";
        changed();
        DriveClient client = new DriveClient(token);
        worker.execute(() -> {
            try {
                // Recheck before creation in case another device already created it.
                List<DriveClient.Folder> available = client.folders();
                if (available.isEmpty()) available.add(client.createFolder());
                main.post(() -> {
                    folders.clear();
                    folders.addAll(available);
                    busy = false;
                    if (available.size() == 1) selectFolder(available.get(0).id);
                    else { status = "Choose the directory to use."; changed(); }
                });
            } catch (Exception error) { main.post(() -> failed(error)); }
        });
    }

    void testRoundTrip() {
        if (busy || !connected() || folderId.isEmpty()) return;
        busy = true;
        status = "Uploading a disposable two-page notebook, then checking its downloaded bytes…";
        changed();
        DriveClient client = new DriveClient(token);
        String expectedAccount = accountId, selectedFolder = folderId;
        worker.execute(() -> {
            try {
                DriveClient.require(expectedAccount.equals(client.account().getString("permissionId")),
                    "Drive account changed. Reconnect before continuing.");
                byte[] payload = DriveClient.read(getAssets().open("connection-test.note"));
                String hash = client.roundTrip(selectedFolder, payload);
                main.post(() -> {
                    preferences().edit().putString("testedAccount", expectedAccount)
                        .putString("testedFolder", selectedFolder).putString("testSha256", hash).apply();
                    busy = false;
                    status = "Drive round trip passed. The downloaded notebook matches every byte. " +
                        "The disposable upload is in Drive Trash.\n\nAutomatic notebook sync is not enabled.";
                    changed();
                });
            } catch (Exception error) { main.post(() -> failed(error)); }
        });
    }

    private RevisionQueue queue() throws Exception {
        return new RevisionQueue(new java.io.File(getFilesDir(), "revisions"));
    }

    private RevisionDriveStore revisionStore(DriveClient client, String account, String folder) throws Exception {
        String binding = DriveClient.digest("SHA-256", (account + ":" + folder)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new RevisionDriveStore(client, folder,
            new java.io.File(getFilesDir(), "verified-drive-cache/" + binding));
    }

    String boundAccount() { return accountId; }

    void resolveConflict(String reviewedAccount, String reviewedFolder, String notebook,
            List<String> heads, String selected) {
        if (!connected()) return;
        if (!reviewedAccount.equals(accountId) || !reviewedFolder.equals(folderId)) {
            status = "The Drive connection changed. Review versions again.";
            changed();
            return;
        }
        if (busy) {
            if (deferredConflict == null) {
                deferredConflict = () -> resolveConflict(reviewedAccount, reviewedFolder, notebook, heads, selected);
                main.postDelayed(this::runDeferredConflict, 500);
            }
            status = "Your version choice is waiting for the current sync check to finish.";
            changed();
            return;
        }
        busy = true;
        status = "Checking reviewed versions before saving your choice…";
        changed();
        DriveClient client = new DriveClient(token);
        worker.execute(() -> {
            try {
                requireAccount(client, reviewedAccount);
                RevisionDriveStore store = revisionStore(client, reviewedAccount, reviewedFolder);
                RevisionCatalog current = store.load();
                String device = preferences().getString("device", "");
                DriveClient.require(!device.isEmpty(), "Enable sync to create this device's identity first");
                LibraryQueue library = new LibraryQueue(new java.io.File(getFilesDir(), "library"));
                synchronized (LibraryQueue.LOCK) {
                    Revision choice = ConflictResolution.prepare(current, notebook, heads, selected, device,
                        library.branch(reviewedAccount, reviewedFolder, notebook));
                    queue().enqueue(reviewedAccount, reviewedFolder, choice,
                        choice.payload == null ? null : current.payloads.get(choice.payload));
                }
                // A later concurrent publication remains a visible conflict; no branch is discarded.
                publishPending(client, reviewedAccount, reviewedFolder);
            } catch (Exception error) { main.post(() -> revisionFailed(error)); }
        });
    }

    private void runDeferredConflict() {
        if (deferredConflict == null) return;
        if (busy) { main.postDelayed(this::runDeferredConflict, 500); return; }
        Runnable choice = deferredConflict;
        deferredConflict = null;
        choice.run();
    }

    void refreshRevisions() {
        if (busy || !connected() || folderId.isEmpty()) return;
        busy = true;
        catalog = null;
        status = "Downloading and verifying notebook revisions…";
        changed();
        String expectedAccount = accountId, selectedFolder = folderId;
        DriveClient client = new DriveClient(token);
        worker.execute(() -> {
            try {
                requireAccount(client, expectedAccount);
                RevisionCatalog result = revisionStore(client, expectedAccount, selectedFolder).load();
                saveCatalog(result, expectedAccount, selectedFolder);
                String summary = catalogSummary(result, queue().pending());
                main.post(() -> {
                    catalog = result;
                    revisionStatus = summary;
                    busy = false;
                    status = "Revisions verified. No local notebook was imported or replaced.";
                    changed();
                });
            } catch (Exception error) { main.post(() -> failed(error)); }
        });
    }

    void publishFixture() {
        if (busy || !connected() || folderId.isEmpty() || catalog == null) return;
        List<String> parents = catalog.heads(VALIDATION_NOTEBOOK);
        if (parents.size() > 1) {
            status = "Conflicting revisions are preserved. Resolve them before publishing another fixture.";
            changed();
            return;
        }
        busy = true;
        status = "Saving the disposable revision to the local journal, then publishing to Drive…";
        changed();
        String expectedAccount = accountId, selectedFolder = folderId;
        String device = preferences().getString("device", "");
        if (device.isEmpty()) {
            device = "android-" + java.util.UUID.randomUUID();
            if (!preferences().edit().putString("device", device).commit()) {
                busy = false;
                status = "Could not save this device’s sync identity.";
                changed();
                return;
            }
        }
        String deviceId = device;
        DriveClient client = new DriveClient(token);
        worker.execute(() -> {
            try {
                byte[] bytes = DriveClient.read(getAssets().open("connection-test.note"));
                Revision revision = new Revision(VALIDATION_NOTEBOOK, deviceId,
                    DriveClient.digest("SHA-256", bytes), parents);
                queue().enqueue(expectedAccount, selectedFolder, revision, bytes);
                publishPending(client, expectedAccount, selectedFolder);
            } catch (Exception error) { main.post(() -> revisionFailed(error)); }
        });
    }

    void retryRevisions() {
        if (busy || !connected() || folderId.isEmpty()) return;
        busy = true;
        status = "Retrying saved revisions for this Drive account and directory…";
        changed();
        String expectedAccount = accountId, selectedFolder = folderId;
        DriveClient client = new DriveClient(token);
        worker.execute(() -> {
            try { publishPending(client, expectedAccount, selectedFolder); }
            catch (Exception error) { main.post(() -> revisionFailed(error)); }
        });
    }

    private void publishPending(DriveClient client, String account, String folder) throws Exception {
        requireAccount(client, account);
        RevisionDriveStore store = revisionStore(client, account, folder);
        RevisionQueue journal = queue();
        int published = journal.retry(account, folder, store::publish);
        RevisionCatalog result = store.load();
        saveCatalog(result, account, folder);
        String summary = catalogSummary(result, journal.pending());
        main.post(() -> {
            catalog = result;
            revisionStatus = summary;
            busy = false;
            status = published + " saved revision(s) published and verified. Linked BOOX devices can download them.";
            changed();
        });
    }

    private JSONObject requireAccount(DriveClient client, String expected) throws Exception {
        JSONObject account = client.account();
        DriveClient.require(expected.equals(account.getString("permissionId")),
            "Drive account changed. Reconnect before continuing.");
        return account;
    }

    private void saveCatalog(RevisionCatalog result, String account, String folder) throws Exception {
        String binding = DriveClient.digest("SHA-256",
            (account + ":" + folder).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.io.File root = new java.io.File(getFilesDir(), "incoming");
        java.io.File directory = new java.io.File(root, binding);
        DriveClient.require(directory.isDirectory() || directory.mkdirs(), "Cannot stage incoming revisions");
        RevisionQueue.syncDirectory(root);
        RevisionQueue.syncDirectory(getFilesDir());
        for (java.util.Map.Entry<String, byte[]> item : result.payloads.entrySet())
            RevisionQueue.write(new java.io.File(directory, "payload-" + item.getKey() + ".note"), item.getValue());
        org.json.JSONArray ids = new org.json.JSONArray();
        for (java.util.Map.Entry<String, Revision> item : result.revisions.entrySet()) {
            RevisionQueue.write(new java.io.File(directory, "revision-" + item.getKey() + ".json"), item.getValue().encode());
            ids.put(item.getKey());
        }
        // Publish the cached catalog only after all referenced bytes are durable.
        JSONObject snapshot = new JSONObject().put("schema", 1).put("account", account)
            .put("folder", folder).put("revisions", ids);
        RevisionQueue.write(new java.io.File(directory, "catalog.json"),
            snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String catalogSummary(RevisionCatalog result, int pending) {
        java.util.Set<String> items = new java.util.HashSet<>();
        for (Revision revision : result.revisions.values())
            if (!VALIDATION_NOTEBOOK.equals(revision.notebook)) items.add(revision.notebook);
        int notebooks = 0, folders = 0, conflicts = 0;
        for (String item : items) {
            List<String> heads = result.heads(item);
            if (heads.size() > 1) conflicts++;
            boolean live = false;
            for (String head : heads) if (result.revisions.get(head).payload != null) live = true;
            if (!live) continue;
            if (item.startsWith("folder-")) folders++;
            else if (item.startsWith("boox-")) notebooks++;
        }
        return "Stored notebooks: " + notebooks + " · Folders: " + folders +
            "\nPending uploads: " + pending + " · Items needing conflict review: " + conflicts;
    }

    private void revisionFailed(Exception error) {
        catalog = null;
        failed(error);
        status += "\nSaved revisions remain queued. Reconnect if needed, then retry pending uploads.";
        changed();
    }

    private void failed(Exception error) {
        busy = false;
        if (error instanceof DriveClient.Unauthorized) {
            String expired = token;
            token = null;
            if (expired != null) Identity.getAuthorizationClient(this)
                .clearToken(ClearTokenRequest.builder().setToken(expired).build());
            status = "Drive access expired or was revoked. Reconnect to continue.";
        } else {
            status = error instanceof IOException ? error.getMessage() :
                "Drive returned an unexpected response. Reconnect and try again.";
            status += "\nIf the upload was interrupted, a disposable connection-test file may remain in Drive.";
        }
        changed();
    }
}
