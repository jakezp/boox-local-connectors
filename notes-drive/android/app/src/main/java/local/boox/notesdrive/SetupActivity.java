package local.boox.notesdrive;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class SetupActivity extends Activity {
    private static final int AUTHORIZE = 81;
    private DriveSession session;
    private TextView status, account, revisions, automaticStatus;
    private android.widget.Switch automatic, incoming, reader;
    private TextView readingStatus;
    private Spinner folders;
    private Button connect, create, test, refresh, publish, retry, conflicts;
    private boolean rendering;
    private final Runnable update = this::render;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        session = (DriveSession) getApplication();
        ScrollView scroll = new ScrollView(this);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(28), dp(24), dp(36));
        body.setBackgroundColor(Color.WHITE);
        scroll.addView(body);
        SettingsStyle.screen(this, scroll, body, "Google Drive Settings");
        SettingsStyle.section(body, "Google account");
        text(body, "Books, reading data and notebooks on your linked BOOX devices.", 16);
        account = text(body, "", 18);
        status = text(body, "", 20);
        connect = button(body, "Connect Google Drive", v -> session.connect());
        SettingsStyle.section(body, "Notebook sync");
        automatic = new android.widget.Switch(this);
        automatic.setText("Automatically publish saved notebooks");
        automatic.setTextSize(18);
        automatic.setPadding(0, dp(12), 0, dp(12));
        body.addView(automatic);
        SettingsStyle.control(automatic);
        automatic.setOnCheckedChangeListener((view, checked) -> {
            if (!rendering) session.setAutomatic(checked);
        });
        incoming = new android.widget.Switch(this);
        incoming.setText("Automatically apply incoming notebook edits");
        incoming.setTextSize(18);
        incoming.setPadding(0, dp(12), 0, dp(12));
        body.addView(incoming);
        SettingsStyle.control(incoming);
        incoming.setOnCheckedChangeListener((view, checked) -> {
            if (!rendering) session.setIncoming(checked);
        });
        automaticStatus = text(body, "", 16);
        button(body, "Retry incoming updates", v -> session.retryIncoming());
        conflicts = button(body, "Review conflicting versions", v -> ConflictReview.show(this, session));
        text(body, "Open Notes after enabling. Unlocked local notebooks up to 4 MiB are supported. " +
            "Android may defer background work during sleep. Notes sync controls use Google Drive " +
            "while the replacement is configured.", 16);
        SettingsStyle.section(body, "Books and reading data");
        reader = new android.widget.Switch(this);
        reader.setText("Automatically sync books and reading data"); body.addView(reader); SettingsStyle.control(reader);
        reader.setOnCheckedChangeListener((view, checked) -> { if (!rendering) session.setReader(checked); });
        readingStatus = text(body, "", 16);
        button(body, "Sync books now", v -> session.requestAutomaticSync());
        button(body, "Review conflicting reading versions", v -> ReaderConflictReview.show(this, session));
        text(body, "Close NeoReader to publish or receive reading progress, bookmarks, annotations and handwritten book notes. " +
            "Use this same directory on every linked BOOX. Encrypted books require their original provider.", 16);
        SettingsStyle.section(body, "Sync directory");
        folders = new Spinner(this);
        body.addView(folders, new LinearLayout.LayoutParams(-1, dp(64)));
        SettingsStyle.control(folders);
        folders.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!rendering && position > 0 && position <= session.folders.size())
                    session.selectFolder(session.folders.get(position - 1).id);
            }
        });
        create = button(body, "Create BOOX Notes Sync directory", v -> session.createFolder());
        test = button(body, "Test Drive round trip", v -> session.testRoundTrip());
        text(body, "The test uploads only a bundled disposable notebook, verifies the download, " +
            "then moves that test file to Drive Trash.", 16);
        SettingsStyle.section(body, "Library sync status");
        revisions = text(body, "", 17);
        refresh = button(body, "Refresh verified revisions", v -> session.refreshRevisions());
        retry = button(body, "Retry pending revision uploads", v -> session.retryRevisions());
        text(body, "On a new BOOX, connect to the existing directory and enable both automatic settings. " +
            "Open Notes and leave its editors closed while stored notebooks download. " +
            "Keep the device awake and connected during the first sync.", 16);
        LinearLayout diagnostics = new LinearLayout(this);
        diagnostics.setOrientation(LinearLayout.VERTICAL);
        diagnostics.setVisibility(View.GONE);
        button(body, "Show / hide sync diagnostics", v ->
            diagnostics.setVisibility(diagnostics.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        body.addView(diagnostics);
        publish = button(diagnostics, "Publish disposable notebook revision", v -> session.publishFixture());
        text(diagnostics, "This optional diagnostic retains a generated test revision in the sync directory. " +
            "It is not needed for normal notebook sync.", 16);
        button(body, "Copy Google registration details", v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Google Android app registration", registration()));
            android.widget.Toast.makeText(this, "Registration details copied", android.widget.Toast.LENGTH_SHORT).show();
        });
        setContentView(scroll);
    }

    @Override protected void onStart() { super.onStart(); session.attach(update); }
    @Override protected void onStop() { session.detach(update); super.onStop(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        // Google can return an explanatory status in a cancelled result intent.
        if (request == AUTHORIZE) session.authorizationResult(data);
    }

    private void render() {
        rendering = true;
        status.setText(session.status);
        account.setText(session.accountLabel);
        revisions.setText(session.revisionStatus);
        automatic.setChecked(session.automaticEnabled());
        automatic.setEnabled(!session.busy && (session.automaticEnabled() ||
            (session.connected() && !session.folderId.isEmpty())));
        automaticStatus.setText(session.automaticStatus());
        reader.setChecked(session.readerEnabled());
        reader.setEnabled(session.readerEnabled() || (session.connected() && !session.folderId.isEmpty()));
        readingStatus.setText(session.readerStatus());
        incoming.setChecked(session.incomingEnabled());
        incoming.setEnabled(!session.busy && session.automaticEnabled());
        connect.setEnabled(!session.busy);
        connect.setText(session.connected() ? "Reconnect / refresh directories" : "Connect Google Drive");
        create.setEnabled(!session.busy && session.connected() && session.folders.isEmpty());
        test.setEnabled(!session.busy && session.connected() && !session.folderId.isEmpty());
        refresh.setEnabled(test.isEnabled());
        retry.setEnabled(test.isEnabled());
        publish.setEnabled(test.isEnabled() && session.catalog != null);
        conflicts.setEnabled(session.connected() && !session.folderId.isEmpty() && session.catalog != null);
        folders.setEnabled(!session.busy && session.connected() && !session.folders.isEmpty());
        List<String> names = new ArrayList<>();
        names.add("Choose a sync directory");
        int selected = 0;
        for (DriveClient.Folder folder : session.folders) {
            names.add(folder.toString());
            if (folder.id.equals(session.folderId)) selected = names.size() - 1;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        folders.setAdapter(adapter);
        folders.setSelection(selected, false);
        rendering = false;
        if (session.resolution != null) {
            android.app.PendingIntent pending = session.resolution;
            session.resolution = null;
            try { startIntentSenderForResult(pending.getIntentSender(), AUTHORIZE, null, 0, 0, 0); }
            catch (Exception error) { session.authorizationFailed(error); }
        }
    }

    private String registration() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            byte[] cert = info.signingInfo.getApkContentsSigners()[0].toByteArray();
            StringBuilder fingerprint = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-1").digest(cert)) {
                if (fingerprint.length() > 0) fingerprint.append(':');
                fingerprint.append(String.format(java.util.Locale.ROOT, "%02X", value & 255));
            }
            return "Application type: Android\nPackage: " + getPackageName() +
                "\nSHA-1: " + fingerprint + "\nScope: " + DriveSession.SCOPE;
        } catch (Exception error) { return "Could not read the app signing certificate."; }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout body, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.BLACK);
        view.setPadding(0, dp(8), 0, dp(12));
        body.addView(view, new LinearLayout.LayoutParams(-1, -2));
        SettingsStyle.text(view, Math.min(size, 18));
        return view;
    }
    private Button button(LinearLayout body, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        body.addView(button, params);
        SettingsStyle.control(button);
        return button;
    }
}
