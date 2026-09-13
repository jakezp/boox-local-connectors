package local.boox.notesdrive;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;

/** A folder has its own revision ancestry, including when it contains no notebooks. */
final class FolderRecord {
    final String id, parent, title;
    FolderRecord(String id, String parent, String title) throws Exception {
        DriveClient.require(Revision.identifier(id) && id.length() <= 100 &&
            (parent == null || Revision.identifier(parent)) && !id.equals(parent),
            "Invalid folder identity");
        DriveClient.require(title != null && !title.isEmpty() && title.length() <= 1000 &&
            title.indexOf('\0') < 0, "Invalid folder title");
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isHighSurrogate(c)) {
                DriveClient.require(i + 1 < title.length() &&
                    Character.isLowSurrogate(title.charAt(++i)), "Invalid folder Unicode");
            } else DriveClient.require(!Character.isLowSurrogate(c), "Invalid folder Unicode");
        }
        this.id = id; this.parent = parent; this.title = title;
    }
    byte[] encode() {
        return ("{\"id\":" + quote(id) + ",\"kind\":\"folder\",\"parent\":" +
            (parent == null ? "null" : quote(parent)) + ",\"schema\":1,\"title\":" +
            quote(title) + "}").getBytes(StandardCharsets.UTF_8);
    }
    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 32) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else result.append(c);
            }
        }
        return result.append('"').toString();
    }
    static FolderRecord decode(byte[] bytes) throws Exception {
        DriveClient.require(bytes.length <= 16384, "Folder record exceeds limit");
        JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        DriveClient.require(value.length() == 5 && "folder".equals(value.getString("kind")) &&
            value.get("schema") instanceof Integer && value.getInt("schema") == 1 &&
            value.get("id") instanceof String && value.get("title") instanceof String &&
            (value.get("parent") == JSONObject.NULL || value.get("parent") instanceof String),
            "Invalid folder record");
        FolderRecord folder = new FolderRecord(value.getString("id"),
            value.isNull("parent") ? null : value.getString("parent"), value.getString("title"));
        DriveClient.require(Arrays.equals(bytes, folder.encode()), "Noncanonical folder record");
        return folder;
    }
}
