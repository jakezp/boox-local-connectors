package local.boox.notesdrive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Canonical, content-addressed revision shared with the Python and Mac clients. */
final class Revision {
    final String notebook, device, payload;
    final List<String> parents;

    Revision(String notebook, String device, String payload, List<String> parents) throws Exception {
        DriveClient.require(identifier(notebook) && identifier(device), "Invalid revision identity");
        DriveClient.require(payload == null || hash(payload), "Invalid payload hash");
        DriveClient.require(parents != null && parents.size() <= 64, "Invalid revision parents");
        List<String> ordered = new ArrayList<>(parents);
        for (String parent : ordered) DriveClient.require(hash(parent), "Invalid parent hash");
        Collections.sort(ordered);
        DriveClient.require(new java.util.HashSet<>(ordered).size() == ordered.size(),
            "Repeated revision parent");
        this.notebook = notebook;
        this.device = device;
        this.payload = payload;
        this.parents = Collections.unmodifiableList(ordered);
    }

    byte[] encode() {
        StringBuilder out = new StringBuilder("{\"deleted\":").append(payload == null)
            .append(",\"device\":\"").append(device).append("\",\"notebook\":\"").append(notebook)
            .append("\",\"parents\":[");
        for (int i = 0; i < parents.size(); i++) {
            if (i != 0) out.append(',');
            out.append('"').append(parents.get(i)).append('"');
        }
        out.append("],\"payload\":").append(payload == null ? "null" : "\"" + payload + "\"")
            .append(",\"schema\":1}");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    String id() throws Exception { return DriveClient.digest("SHA-256", encode()); }

    static Revision decode(byte[] bytes) throws Exception {
        DriveClient.require(bytes.length <= 16384, "Revision exceeds limit");
        JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        DriveClient.require(value.length() == 6 && value.get("device") instanceof String &&
            value.get("notebook") instanceof String && value.get("deleted") instanceof Boolean &&
            value.get("parents") instanceof JSONArray &&
            (value.get("schema") instanceof Integer || value.get("schema") instanceof Long) &&
            value.getLong("schema") == 1, "Unsupported revision record");
        Object payload = value.get("payload");
        DriveClient.require(payload == JSONObject.NULL || payload instanceof String,
            "Invalid revision payload");
        DriveClient.require(value.getBoolean("deleted") == (payload == JSONObject.NULL),
            "Invalid deletion record");
        List<String> parents = new ArrayList<>();
        JSONArray array = value.getJSONArray("parents");
        for (int i = 0; i < array.length(); i++) {
            DriveClient.require(array.get(i) instanceof String, "Invalid revision parent");
            parents.add(array.getString(i));
        }
        Revision result = new Revision(value.getString("notebook"), value.getString("device"),
            payload == JSONObject.NULL ? null : (String) payload, parents);
        // Also rejects duplicate keys, reordered/extra keys, floats, escaped IDs and trailing text.
        DriveClient.require(Arrays.equals(bytes, result.encode()), "Noncanonical revision record");
        return result;
    }

    static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
    }
    static boolean hash(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }
}
