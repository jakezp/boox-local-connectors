package local.boox.openai;

import org.json.JSONObject;

interface ReplyClient {
    String complete(JSONObject request) throws Exception;
    void cancel();
    boolean isCancelled();
}
