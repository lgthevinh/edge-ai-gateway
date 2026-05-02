package thingai.edge.aigateway.agent.tools;

import com.google.gson.JsonObject;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.utils.JsonUtil;

import java.nio.file.Files;
import java.nio.file.Path;

public class ReadFileTool implements IAgentTool {

    @Override
    public String getName() { return "read_file"; }

    @Override
    public String getDescription() { return "Read the contents of a file at the given absolute path"; }

    @Override
    public String getParametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Absolute file path to read\"}},\"required\":[\"path\"]}";
    }

    @Override
    public String execute(String paramsJson) {
        JsonObject params = JsonUtil.fromJson(paramsJson, JsonObject.class);
        String path = params.get("path").getAsString();
        try {
            String content = Files.readString(Path.of(path));
            JsonObject result = new JsonObject();
            result.addProperty("content", content);
            return JsonUtil.toJson(result);
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return JsonUtil.toJson(error);
        }
    }
}
