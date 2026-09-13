package local.boox.notesdrive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded native archive inspector. Stable identities are never remapped. */
final class NativeArchive {
    final String id, title, parent;
    final Map<String, byte[]> files;
    final List<String> pages = new ArrayList<>();
    final Map<String, Map<String, byte[]>> shapes = new LinkedHashMap<>();
    final Map<String, Map<String, String>> points = new LinkedHashMap<>();
    final byte[] noteInfo;
    final JSONObject pageInfo;
    final Map<String, Set<String>> sharedIdentities = new TreeMap<>();

    NativeArchive(byte[] bytes, String id) throws Exception {
        NativeSnapshot.fingerprint(bytes, id);
        this.id = id;
        Map<String, byte[]> outer = unzip(bytes, 64 * 1024 * 1024);
        files = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : outer.entrySet()) {
            DriveClient.require(entry.getKey().startsWith(id + "/"), "Foreign archive root");
            files.put(entry.getKey().substring(id.length() + 1), entry.getValue());
        }
        noteInfo = files.get("note/pb/note_info");
        DriveClient.require(noteInfo != null, "Missing notebook metadata");
        byte[] selected = null;
        for (Object value : protobuf(noteInfo).getOrDefault(1, Collections.emptyList())) {
            Map<Integer, List<Object>> note = protobuf((byte[]) value);
            if (id.equals(text(note, 1))) {
                DriveClient.require(selected == null, "Duplicate notebook identity");
                selected = (byte[]) value;
            }
        }
        DriveClient.require(selected != null, "Notebook identity differs from revision");
        Map<Integer, List<Object>> note = protobuf(selected);
        title = text(note, 6);
        parent = text(note, 4);
        pageInfo = new JSONObject(text(note, 12)).getJSONObject("pageInfoMap");
        JSONArray list = new JSONObject(text(note, 20)).getJSONArray("pageNameList");
        DriveClient.require(list.length() > 0 && list.length() <= 2000, "Invalid native page count");
        for (int i = 0; i < list.length(); i++) {
            String page = list.getString(i);
            DriveClient.require(Revision.identifier(page) && !shapes.containsKey(page), "Invalid or repeated page");
            pages.add(page);
            shapes.put(page, new LinkedHashMap<>());
            points.put(page, new LinkedHashMap<>());
        }
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("point/") && name.endsWith("#points")) {
                String[] parts = name.split("/");
                DriveClient.require(parts.length == 3 && points.containsKey(parts[1]), "Unknown point page");
                addPoints(entry.getValue(), parts[1], points.get(parts[1]));
            } else if (name.startsWith("shape/") && name.endsWith(".zip")) {
                DriveClient.require(name.indexOf('/', 6) < 0, "Nested active shape archive");
                String page = name.substring(6).split("#")[0];
                DriveClient.require(shapes.containsKey(page), "Unknown active shape page");
                Map<String, byte[]> nested = unzip(entry.getValue(), 32 * 1024 * 1024);
                DriveClient.require(nested.size() == 1, "Invalid shape container");
                for (Object raw : protobuf(nested.values().iterator().next())
                        .getOrDefault(1, Collections.emptyList())) {
                    Map<Integer, List<Object>> fields = protobuf((byte[]) raw);
                    String shape = text(fields, 1);
                    DriveClient.require(Revision.identifier(shape), "Invalid shape identity");
                    DriveClient.require(shapes.get(page).put(shape, (byte[]) raw) == null,
                        "Ambiguous duplicate active shape");
                }
            }
        }
        // An exported snapshot must not silently omit point data for ordinary pen strokes.
        for (String page : pages) for (Map.Entry<String, byte[]> shape : shapes.get(page).entrySet()) {
            Map<Integer, List<Object>> fields = protobuf(shape.getValue());
            long kind = number(fields, 12, 0), status = number(fields, 15, 0);
            if (kind == 2 && status == 0)
                DriveClient.require(points.get(page).containsKey(shape.getKey()), "Pen point data missing");
        }
        for (String kind : Arrays.asList("resource", "tag", "link")) {
            String table = kind.equals("resource") ? "ResourceModel" : kind.equals("tag") ? "TagModel" : "LinkModel";
            Set<String> identities = new TreeSet<>();
            sharedIdentities.put(table, identities);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                if (!file.getKey().startsWith(kind + "/pb/")) continue;
                for (Object raw : protobuf(file.getValue()).getOrDefault(1, Collections.emptyList())) {
                    Map<Integer, List<Object>> fields = protobuf((byte[]) raw);
                    String identity = text(fields, 1), owner = text(fields, kind.equals("link") ? 3 : 2);
                    DriveClient.require(Revision.identifier(identity) && id.equals(owner),
                        "Shared native record has a foreign owner");
                    identities.add(identity);
                    if (kind.equals("resource")) {
                        String relative = text(fields, 8);
                        for (String segment : relative.split("/", -1))
                            DriveClient.require(!segment.equals("..") && !segment.equals("."),
                                "Unsafe native resource path");
                        DriveClient.require(!relative.contains("\\") && !relative.contains("\u0000"),
                            "Unsafe native resource path");
                        if (number(fields, 13, 0) == 0) {
                            String path = "resource/data/" + (relative.startsWith("/") ? relative.substring(1) : relative);
                            DriveClient.require(!relative.isEmpty() && files.containsKey(path),
                                "Resource bytes must be present inside the notebook archive");
                        }
                    }
                }
            }
        }
    }

    static Map<String, byte[]> unzip(byte[] bytes, int maximum) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int total = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                DriveClient.require(name.length() <= 1024 && !name.startsWith("/") &&
                    !name.contains("\\") && !name.contains("\u0000") && seen.size() < 10000 &&
                    seen.add(name), "Unsafe or duplicate archive entry");
                for (String part : name.split("/", -1))
                    DriveClient.require(!part.equals("..") && !part.equals("."), "Unsafe archive segment");
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] block = new byte[16384];
                int count;
                while ((count = input.read(block)) != -1) {
                    total += count;
                    DriveClient.require(total <= maximum, "Expanded archive exceeds limit");
                    output.write(block, 0, count);
                }
                result.put(name, output.toByteArray());
            }
        }
        return result;
    }

    static Map<Integer, List<Object>> protobuf(byte[] bytes) throws Exception {
        Map<Integer, List<Object>> fields = new TreeMap<>();
        ByteBuffer input = ByteBuffer.wrap(bytes);
        while (input.hasRemaining()) {
            long tag = varint(input);
            int field = (int) (tag >>> 3), wire = (int) (tag & 7);
            DriveClient.require(field > 0 && tag >>> 3 <= 536870911, "Invalid protobuf field");
            Object value;
            if (wire == 0) value = varint(input);
            else {
                DriveClient.require(wire == 1 || wire == 2 || wire == 5, "Unsupported protobuf wire");
                long length = wire == 2 ? varint(input) : wire == 1 ? 8 : 4;
                DriveClient.require(length >= 0 && length <= input.remaining(), "Truncated protobuf value");
                byte[] raw = new byte[(int) length]; input.get(raw); value = raw;
            }
            fields.computeIfAbsent(field, ignored -> new ArrayList<>()).add(value);
        }
        return fields;
    }
    static long varint(ByteBuffer input) throws Exception {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            DriveClient.require(input.hasRemaining(), "Truncated protobuf integer");
            int b = input.get() & 255;
            DriveClient.require(shift != 63 || b <= 1, "Protobuf integer overflow");
            value |= (long) (b & 127) << shift;
            if (b < 128) return value;
        }
        throw new IllegalArgumentException("Invalid protobuf integer");
    }
    static Object scalar(Map<Integer, List<Object>> fields, int number, Object fallback) throws Exception {
        List<Object> values = fields.get(number);
        if (values == null) return fallback;
        DriveClient.require(values.size() == 1, "Duplicate scalar field");
        return values.get(0);
    }
    static String text(Map<Integer, List<Object>> fields, int number) throws Exception {
        Object value = scalar(fields, number, new byte[0]);
        DriveClient.require(value instanceof byte[], "Invalid protobuf text");
        return new String((byte[]) value, StandardCharsets.UTF_8);
    }
    static long number(Map<Integer, List<Object>> fields, int number, long fallback) throws Exception {
        Object value = scalar(fields, number, fallback);
        DriveClient.require(value instanceof Long, "Invalid protobuf number");
        return (Long) value;
    }
    private static void addPoints(byte[] data, String page, Map<String, String> records) throws Exception {
        DriveClient.require(data.length >= 80, "Truncated native points");
        ByteBuffer input = ByteBuffer.wrap(data);
        DriveClient.require(input.getShort() == 0 && input.getShort() == 1, "Unsupported point version");
        byte[] pageBytes = new byte[36]; input.get(pageBytes);
        DriveClient.require(page.equals(new String(pageBytes, StandardCharsets.US_ASCII).trim()),
            "Point header page differs");
        int index = input.getInt(data.length - 4), expected = 76;
        DriveClient.require(index >= 76 && index <= data.length - 4 &&
            (data.length - 4 - index) % 44 == 0, "Invalid point index");
        for (int position = index; position < data.length - 4; position += 44) {
            input.position(position);
            byte[] idBytes = new byte[36]; input.get(idBytes);
            String id = new String(idBytes, StandardCharsets.US_ASCII).trim();
            int offset = input.getInt(), length = input.getInt();
            DriveClient.require(Revision.identifier(id) && offset == expected && length >= 4 &&
                (length - 4) % 16 == 0 && length <= index - offset, "Invalid point block");
            String hash = DriveClient.digest("SHA-256", Arrays.copyOfRange(data, offset, offset + length));
            DriveClient.require(records.put(id, hash) == null, "Duplicate point identity");
            expected += length;
        }
        DriveClient.require(expected == index, "Unindexed native point data");
    }

    void verifyReadback(NativeArchive actual) throws Exception {
        DriveClient.require(id.equals(actual.id) && title.equals(actual.title) && parent.equals(actual.parent) &&
            pages.equals(actual.pages), "Native notebook identity, title, parent or page order changed");
        verifyRecords(actual, "resource/pb/", true, 13, 3, 4, 11);
        verifyRecords(actual, "tag/pb/", true, 9, 5, 6, 9);
        verifyRecords(actual, "link/pb/", true, 19, 7, 8, 19);
        verifyRecords(actual, "virtual/doc/pb/", false, 10, 2, 3);
        verifyRecords(actual, "virtual/page/pb/", true, 13, 2, 3);
        verifyRecords(actual, "pageModel/pb/", true, 7, 5, 6);
        verifyRecords(actual, "toc/pb/", true, 9, 2, 3);
        Map<String, String> expectedAssets = assetHashes(), actualAssets = actual.assetHashes();
        for (Map.Entry<String, String> asset : expectedAssets.entrySet())
            DriveClient.require(asset.getValue().equals(actualAssets.get(asset.getKey())),
                "Native attachments, templates or retained history differ");
        for (String path : actualAssets.keySet())
            DriveClient.require(expectedAssets.containsKey(path) || path.startsWith("stash/") ||
                ("extra/pb/extra".equals(path) && actual.generatedExportInfo()),
                "Native export introduced an unexpected attachment or template");
        for (String page : pages) {
            Set<String> live = liveShapes(page), actualLive = actual.liveShapes(page);
            DriveClient.require(live.equals(actualLive),
                "Native stroke identities changed");
            Map<String, String> livePoints = new TreeMap<>(), actualPoints = new TreeMap<>();
            for (String shape : live) {
                if (points.get(page).containsKey(shape)) livePoints.put(shape, points.get(page).get(shape));
                if (actual.points.get(page).containsKey(shape)) actualPoints.put(shape, actual.points.get(page).get(shape));
                DriveClient.require(shapeSignature(shapes.get(page).get(shape)).equals(
                    shapeSignature(actual.shapes.get(page).get(shape))), "Native stroke content differs");
            }
            DriveClient.require(livePoints.equals(actualPoints), "Native point readback differs");
            JSONObject before = pageInfo.getJSONObject(page), after = actual.pageInfo.getJSONObject(page);
            for (String field : Arrays.asList("width", "height", "layerList"))
                DriveClient.require(canonicalJson(before.get(field)).equals(canonicalJson(after.get(field))),
                    "Native page dimensions or layers differ");
        }
    }

    private boolean generatedExportInfo() throws Exception {
        Map<Integer, List<Object>> extra = protobuf(files.get("extra/pb/extra"));
        return new HashSet<>(Arrays.asList(1, 2, 3, 4)).containsAll(extra.keySet()) &&
            number(extra, 1, 0) == 1 && number(extra, 2, 0) == 45326 &&
            id.equals(text(extra, 4)) &&
            (text(extra, 3).isEmpty() || Revision.identifier(text(extra, 3)));
    }

    private Map<String, String> assetHashes() throws Exception {
        Map<String, String> assets = new TreeMap<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String path = file.getKey();
            if (path.startsWith("resource/data/") || path.startsWith("template/") ||
                    path.endsWith(".template_json") || path.startsWith("stash/") || path.startsWith("extra/"))
                assets.put(path, DriveClient.digest("SHA-256", file.getValue()));
        }
        return assets;
    }
    private void verifyRecords(NativeArchive actual, String prefix, boolean wrapped, int max, Integer... ignored)
            throws Exception {
        DriveClient.require(records(prefix, wrapped, max, ignored).equals(actual.records(prefix, wrapped, max, ignored)),
            "Native " + prefix + "content differs");
    }
    private Map<String, String> records(String prefix, boolean wrapped, int max, Integer... ignored) throws Exception {
        Map<String, String> records = new TreeMap<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) if (file.getKey().startsWith(prefix)) {
            List<?> values = wrapped ? protobuf(file.getValue()).getOrDefault(1, Collections.emptyList()) :
                Collections.singletonList(file.getValue());
            for (Object raw : values) {
                Map<Integer, List<Object>> fields = protobuf((byte[]) raw);
                String recordId = text(fields, 1);
                DriveClient.require(Revision.identifier(recordId), "Invalid native metadata record identity");
                String signature = signature(fields, max, Arrays.asList(ignored), Collections.emptyList());
                String previous = records.put(recordId, signature);
                DriveClient.require(previous == null || previous.equals(signature), "Conflicting native metadata records");
            }
        }
        return records;
    }

    private Set<String> liveShapes(String page) throws Exception {
        Set<String> live = new TreeSet<>();
        for (Map.Entry<String, byte[]> item : shapes.get(page).entrySet())
            if (number(protobuf(item.getValue()), 15, 0) == 0) live.add(item.getKey());
        return live;
    }

    static String shapeSignature(byte[] bytes) throws Exception {
        return signature(protobuf(bytes), 26, Arrays.asList(2, 3, 16, 18), Collections.singletonList(5));
    }
    private static String signature(Map<Integer, List<Object>> fields, int knownMax,
            List<Integer> ignored, List<Integer> floats) throws Exception {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<Integer, List<Object>> field : fields.entrySet()) {
            if (ignored.contains(field.getKey())) continue;
            // The inspected proto3 writer omits default-valued singular fields.
            // Mac may encode them explicitly; e.g. zorder=0 is the same layer as absent zorder.
            if (field.getKey() >= 1 && field.getKey() <= knownMax && field.getValue().size() == 1) {
                Object value = field.getValue().get(0);
                if (value instanceof Number && ((Number) value).longValue() == 0) continue;
                if (value instanceof byte[] && ((byte[]) value).length == 0) continue;
                if (floats.contains(field.getKey()) && value instanceof byte[] &&
                        Arrays.equals((byte[]) value, new byte[4])) continue;
            }
            canonical.append(field.getKey()).append('=');
            for (Object value : field.getValue()) {
                if (value instanceof byte[]) {
                    byte[] raw = (byte[]) value;
                    String text = new String(raw, StandardCharsets.UTF_8);
                    if (text.startsWith("{") || text.startsWith("[")) {
                        try { canonical.append(canonicalJson(new org.json.JSONTokener(text).nextValue())); }
                        catch (Exception invalidJson) { canonical.append(DriveClient.digest("SHA-256", raw)); }
                    } else canonical.append(DriveClient.digest("SHA-256", raw));
                } else canonical.append(value);
                canonical.append(';');
            }
            canonical.append('\n');
        }
        return DriveClient.digest("SHA-256", canonical.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String canonicalJson(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Set<String> keys = new TreeSet<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) result.append(JSONObject.quote(key)).append(':')
                .append(canonicalJson(object.get(key))).append(',');
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) result.append(canonicalJson(array.get(i))).append(',');
            return result.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }
}
