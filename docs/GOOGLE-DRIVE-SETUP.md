# Set up Google Drive for Notes sync

This guide connects your own BOOX Notes Drive build and Mac reader to a managed
folder in your Google Drive. You need a Google account, access to Google Cloud
Console, and a completed Notes Android build. No token from another app is needed.

There are two separate steps: **register the apps with Google**, then **sign into
each app**. Registration tells Google which apps are making the request; sign-in
lets your account authorize those apps.

## 1. Create a project and enable Drive

1. Open [Google Cloud Console](https://console.cloud.google.com/).
2. Create a project for your Notes sync deployment, or select a project you own.
   Keep both the Android and Desktop clients in this same project.
3. Open **APIs & Services → Library**, find **Google Drive API**, and enable it.
4. Open **Google Auth Platform** to configure branding and audience. Console
   labels may differ; older layouts call this the OAuth consent screen.
5. Supply your app name and the required contact details. For a personal setup,
   configure an audience that permits your Google account. If the app is in
   testing mode, add the account you will use on both devices as a test user.
6. Under the access/scopes configuration, add only:

   ```text
   https://www.googleapis.com/auth/drive.file
   ```

The apps request this same scope. It is access to app-authorized Drive files,
not unrestricted access to everything in your Drive. See Google's
[Drive scope guide](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).
Google's testing and verification requirements depend on your project/audience;
follow any requirements shown for your account in the console.

## 2. Register the Android app you built

Open `notes-drive/android/registration.json` in your checkout. The Notes builder
generates it from the APK it signed. You need these two values:

| Google client field | Value |
| --- | --- |
| Application type | **Android** |
| Package name | **`local.boox.notesdrive`** |
| SHA-1 certificate fingerprint | Your generated `signing_certificate_sha1` |

In **Google Auth Platform → Clients** (or **APIs & Services → Credentials**),
create an OAuth client with those values. Give it a descriptive name such as
“BOOX Notes Android”. Registering a Desktop client alone will not authorize the
Android package.

The Android companion uses Google Play services authorization. You do not paste
a client secret or Desktop JSON into the Android source. After installation,
**Copy Google registration details** in BOOX Notes Drive helps compare the
installed identity with the client you registered.

Keep `notes-drive/android/local-signing.p12` for future builds. Changing that
key changes the certificate identity, breaks ordinary updates, and requires a
matching Google registration. Other tablets using your same signed APK use the
same package/certificate registration; each still signs in normally.

## 3. Create the Mac client (optional)

Skip this section for BOOX-to-BOOX sync. A Desktop client is needed only for the
optional Mac reader/editor. Each BOOX uses the Android registration and its own sign-in.

In the **same Google Cloud project**:

1. Create another OAuth client, choosing **Desktop app**.
2. Give it a name such as “BOOX Notes Mac”.
3. Download its client JSON and keep it outside your source checkout.
4. Do not choose Web application or enter a callback copied from a past login.
   The Mac app starts its own temporary loopback callback during authorization.

The default Mac build contains no account-specific configuration. Its UI imports
your downloaded JSON after building; you do not need to edit Swift code.

## 4. Connect Android and create the destination

After [installing the companion](../README.md#5-install-the-android-companions):

1. Open **BOOX Notes Drive → Connect Google Drive**.
2. Select the allowed Google account and complete consent.
3. Choose **Create BOOX Notes Sync directory** for a new deployment.
4. Select the managed directory returned by the app.
5. Run **Test Drive round trip** and wait for success.
6. Enable **Automatically publish saved notebooks** and
   **Automatically apply incoming notebook edits**.

For another participating tablet, use its own sign-in and select the same managed
directory rather than creating a second one. A folder name is not unique: compare
identities when more than one appears.

The current apps discover directories carrying their protocol marker. They do
not offer Google Picker to authorize an arbitrary pre-existing folder. Creating
a normal folder manually or knowing its ID is not enough. Let the companion
create the managed directory, and keep its protocol objects intact.

## 5. Connect the Mac

1. Open **BOOX Notes Reader**.
2. Expand **Google Drive connection**.
3. Choose **Import OAuth configuration…** and select the Desktop JSON.
4. Choose **Connect Google Drive…** and complete the browser flow with the same
   Google account. Approve the local Keychain prompt if requested.
5. Select the managed destination created by Android.
6. Enable **Automatic library updates** and use **Check library now**.

A matching Google project and account are necessary, but each client still
checks its own access. If no writable managed directory appears, confirm the
project, account and Android-created directory, then reconnect. Do not work
around it by copying another client's token.

The Mac saves its own grant in Keychain and binds the selected destination to
the imported client and account. Rebuilding can trigger a new Keychain prompt;
choose **Reconnect saved grant** and approve it normally. Changing projects or
clients requires reviewing the existing account/destination binding and queued
work, rather than silently redirecting saved edits.

## 6. Verify native sync

Connection success proves access to Drive. It does not prove that Vector loaded
the Notes hook or that native edits apply correctly. Complete the
[first notebook round trip](../README.md#8-check-your-first-notebook-round-trip)
using a disposable notebook before relying on the shared library.
