package local.boox.openai;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** NeoReader searches this list by document ID; it must include older topic pages. */
final class ReaderConversations {
    interface Bridge { String call(String method, String input) throws Exception; }

    static String load(String input, Bridge bridge) throws Exception {
        JSONObject query = input != null && input.trim().startsWith("{") ? new JSONObject(input) : new JSONObject();
        query.remove("lastDataId");
        query.put("pageSize", 100);
        JSONArray conversations = new JSONArray();
        for (int page = 0; page < 100; page++) {
            query.put("page", page);
            JSONArray batch = new JSONArray(bridge.call("loadConversationList", query.toString()));
            for (int i = 0; i < batch.length(); i++) conversations.put(batch.getJSONObject(i));
            if (batch.length() < 100) return conversations.toString();
        }
        throw new IOException("There are too many topics to find this document. Remove unused topics and try again.");
    }
}
