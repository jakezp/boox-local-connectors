# BOOX Note Air 4C rooting record

> Historical chronology. For current versions, acceptance and next steps, read
> [README.md](README.md) and the newest [HANDOVER.md](HANDOVER.md) section.
> The milestone descriptions below retain their original dates and may be superseded.

Current overall state: [HANDOVER.md](HANDOVER.md). Root is complete; Magisk was later found to be30.7. Vector/Zygisk and a native OpenAI adapter are now installed and tested. The chronology below records the original root work.

Current Notes milestone (2026-09-13): **bidirectional automatic Drive sync and
Mac editing passed live validation**. Android v0.4 and Mac v0.6 support native
incoming edits, folders, moves, recoverable deletion/restoration and explicit
conflict resolution. Mac pen edits render in stock Notes; native changes
automatically update the open Mac notebook. The final nested-folder test and
original-data preservation check passed. See [current validation](docs/VALIDATION.md)
for tested versions, clean-source builds and remaining product limits.

Latest adapter update (2026-09-12): **v0.9** is installed. ChatGPT subscription
sign-in works, the account-model dropdown loads automatically, and **GPT-5.6 Luna**
is selected and live-tested in Setup, native AI Assistant, and NeoReader's embedded
panel (including a contextual follow-up). All 48 regression checks pass. API
credentials and user history are preserved. See HANDOVER and
`openai-adapter/OAUTH-VALIDATION.md` for exact evidence and remaining checks.

Notes sync work (2026-09-12): a disposable existing notebook was updated, reopened
and exported with stable notebook/page/existing stroke IDs. Incoming pen data,
layers and internal links match; an unchanged observer retains its inbound link.
**40 offline tests and 24 live fault/guard cases pass**, plus verified commit checks.
The probe and fixtures are removed; original notebook rows and 182 associated files
match the backup. Wi-Fi/OpenAI scope are restored/preserved. The independent Android
Google authorization/Drive preview v0.1 is installed, with ten passing tests and a
working Google grant. The dedicated project/client is registered; the app-created
BOOX Notes Sync folder is selected. A real disposable `.note` upload/download
passed exact checksums and test-file cleanup. Force-stop/reopen and manual reconnect
retained the same account/folder without another consent prompt. Automatic Notes
sync is not enabled; persisted revision transport and native integration remain.
See [notes-drive/android/README.md](notes-drive/android/README.md) and
[notes-drive/APPLY-VALIDATION.md](notes-drive/APPLY-VALIDATION.md).

Latest continuation: Android **v0.2** adds durable revision uploads/retries and
incoming staging. A subagent built **BOOX Notes Reader macOS v0.1** as the second
test client requested by the user. **22 Android + 25 Mac checks pass**. Live own-client
OAuth, Android offline queue/restart/retry, Mac download/render and Mac descendant
publication back to Android staging all pass. The Mac replaces a second BOOX for
this phase. Automatic Notes library sync/native apply remain off; the next work
is native save/export/app-open/recovery integration.
See [interoperability validation](notes-drive/INTEROPERABILITY-VALIDATION.md).

Device: DEVICE_SERIAL / NoteAir4C
Firmware: 2026-04-28_17-50_4.2-rel_04282_555977efe
Android: 13; running security patch property: 2026-04-01
Original state: bootloader unlocked; active slot B; Magisk app 30.2 installed; su absent.

Backups: backups/DEVICE_SERIAL-4.2-04282 (SHA256.json).
Both boot images, recovery_b, vbmeta_b, devinfo, services.jar and installed Magisk APK saved.
The EDL gpt command reported saved files but its source has file writes commented out; no GPT file backup was actually created. No partition-table writes were performed.

Patched only boot_b with Magisk 30.2 from the installed APK, using its boot_patch.sh on-device.
KEEPVERITY=true, KEEPFORCEENCRYPT=true, PATCHVBMETAFLAG=false, RECOVERYMODE=false.
Original kernel and DTB verified unchanged. Original boot_b and patched image are both 100663296 bytes.
Patched SHA256: 67563496cd465eb44c12e8f5dd3997d94717dcfd89da92be579cace8ef50cd3e
Flashed boot_b via EDL UFS LUN 4; readback matched every byte.
Slot A and all other partitions untouched.

Current stage: restarted to Android; checking persistent root and Magisk UI.

Sources:
https://github.com/jdkruzr/BooxPalma2RootGuide
https://github.com/bkerler/edl
https://github.com/dynamicfire/boox-ams-fix
https://topjohnwu.github.io/Magisk/install.html

## Root and manager fix

Root verified with /debug_ramdisk/su -c id: uid=0(root), SELinux context u:r:magisk:s0.
Magisk manager was disabled; enabled with pm enable com.topjohnwu.magisk.
Confirmed BOOX AMS NullPointerException in addPackageDependency when Magisk RootServerMain starts.
Built patch from ORIGINAL services.jar using tools/build_ams_fix.py.
Only instruction change: DEX offset 0x2b2ff0, branch displacement 0x33 -> 0x3f, redirecting null ProcessRecord to return-void after the monitor lock is released.
Recomputed DEX SHA1 and Adler32; all other instructions and JAR entries verified unchanged.
Original services.jar SHA256: fd45574a43099f3e1abc0bca6a88db3d018d6f183c41d7e0a46f16801ae5b2e6
Patched services.jar SHA256: e2bad92800231d1ad86179202f42dd5f89d830114a7bc6273d25ce9c2c923c14
Module: patch/boox-ams-fix-noteair4c-4.2.zip
Module id: boox_ams_fix_na4c_42
Install-time guard checks device model and original services.jar SHA256.
Restored missing Magisk support files into /data/adb/magisk from the existing Magisk 30.2 APK.
Installed module via magisk --install-module; staged framework hash matched.

Before a firmware update: disable/remove this firmware-specific module. Do not reuse it against another services.jar.
Rollback of module while Android boots: adb shell /debug_ramdisk/su -c 'touch /data/adb/modules/boox_ams_fix_na4c_42/disable', then reboot.
Boot rollback if needed: restore the saved boot_b.img using the verified EDL connection, UFS LUN 4, boot_b only. Do not change slots or flash another model's images.

Final verification: Android boot complete; persistent root uid=0; live framework hash matches patched JAR; Magisk normal UI shows Installed 30.2 (30200), Ramdisk Yes. BOOX launcher Magisk menu showed Unfreeze; selected it and verified menu changed to Freeze. Root task complete.
