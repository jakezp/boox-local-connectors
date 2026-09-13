package local.boox.notesdrive;

import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Offline native-format preflight. Does not connect to Drive or alter notebooks. */
public final class NativeArchiveCheck {
    public static void main(String[] arguments) throws Exception {
        JSONArray results = new JSONArray();
        boolean failed = false;
        for (String argument : arguments) {
            Path path = Paths.get(argument);
            JSONObject result = new JSONObject().put("file", path.getFileName().toString());
            try {
                DriveClient.require(Files.size(path) <= DriveClient.MAX_BYTES, "Archive exceeds transfer bound");
                byte[] bytes = Files.readAllBytes(path);
                Set<String> ids = new HashSet<>();
                for (String name : NativeArchive.unzip(bytes, 64 * 1024 * 1024).keySet())
                    if (name.endsWith("/note/pb/note_info")) ids.add(name.substring(0, name.indexOf('/')));
                DriveClient.require(ids.size() == 1, "Expected one native notebook identity");
                NativeArchive archive = new NativeArchive(bytes, ids.iterator().next());
                archive.verifyReadback(archive);
                result.put("valid", true).put("sha256", DriveClient.digest("SHA-256", bytes))
                    .put("pages", archive.pages.size())
                    .put("shape_records", archive.shapes.values().stream().mapToInt(Map::size).sum());
            } catch (Exception error) {
                failed = true;
                result.put("valid", false).put("error", error.getMessage());
            }
            results.put(result);
        }
        System.out.println(results.toString(2));
        if (failed) System.exit(1);
    }
}
