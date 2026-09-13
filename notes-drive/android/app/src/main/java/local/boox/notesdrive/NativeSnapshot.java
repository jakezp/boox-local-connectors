package local.boox.notesdrive;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounds and fingerprints native archives without extracting or rewriting them. */
final class NativeSnapshot {
    static String fingerprint(byte[] bytes, String nativeId) throws Exception {
        DriveClient.require(nativeId != null && nativeId.matches("[A-Za-z0-9-]{1,100}"),
            "Invalid native notebook identity");
        DriveClient.require(bytes.length > 0 && bytes.length <= DriveClient.MAX_BYTES,
            "Notebook exceeds the current 4 MiB transfer limit");
        Map<String, String> entries = new TreeMap<>();
        HashSet<String> seen = new HashSet<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                DriveClient.require(seen.size() < 10000 && seen.add(name) &&
                    name.startsWith(nativeId + "/") && !name.contains("\\") &&
                    !name.contains("/../") && !name.contains("/./") && name.length() <= 1024,
                    "Invalid or duplicate native archive path");
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                byte[] block = new byte[16384];
                int count;
                while ((count = zip.read(block)) != -1) {
                    total += count;
                    DriveClient.require(total <= 64 * 1024 * 1024, "Expanded notebook exceeds limit");
                    content.write(block, 0, count);
                }
                entries.put(name, DriveClient.digest("SHA-256", content.toByteArray()));
            }
        }
        DriveClient.require(entries.containsKey(nativeId + "/note/pb/note_info"),
            "Native notebook metadata is missing");
        ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(canonical)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
                output.writeInt(name.length);
                output.write(name);
                output.write(entry.getValue().getBytes(StandardCharsets.US_ASCII));
            }
        }
        return DriveClient.digest("SHA-256", canonical.toByteArray());
    }
}
