package thingai.edge.aigateway.agent.tools;

import com.google.gson.JsonObject;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.utils.JsonUtil;

import java.nio.file.Files;
import java.nio.file.Path;

public class ReadFileTool implements IAgentTool {

    private static final int DEFAULT_MAX_CHARS = 8000;

    @Override
    public String getName() { return "read_file"; }

    @Override
    public String getDescription() { return "Read the contents of a file at the given absolute path"; }

    @Override
    public String getParametersJson() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"Absolute file path to read\"},"
                + "\"max_chars\":{\"type\":\"integer\",\"description\":\"Maximum characters to return (default 8000). Content beyond this limit is truncated.\"}"
                + "},\"required\":[\"path\"]}";
    }

    @Override
    public String execute(String paramsJson) {
        JsonObject params = JsonUtil.fromJson(paramsJson, JsonObject.class);
        String path = params.get("path").getAsString();
        int maxChars = params.has("max_chars") ? params.get("max_chars").getAsInt() : DEFAULT_MAX_CHARS;

        try {
            String content = Files.readString(Path.of(path));
            boolean truncated = content.length() > maxChars;
            if (truncated) {
                int omitted = content.length() - maxChars;
                content = content.substring(0, maxChars)
                        + "\n[TRUNCATED — " + omitted + " chars omitted]";
            }
            JsonObject result = new JsonObject();
            result.addProperty("content", content);
            result.addProperty("truncated", truncated);
            return JsonUtil.toJson(result);
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return JsonUtil.toJson(error);
        }
    }
}
