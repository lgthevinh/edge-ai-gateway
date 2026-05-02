package thingai.edge.aigateway.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.utils.JsonUtil;

import java.io.File;

public class ListFilesTool implements IAgentTool {

    @Override
    public String getName() { return "list_files"; }

    @Override
    public String getDescription() { return "List files and directories at the given path"; }

    @Override
    public String getParametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Absolute directory path to list\"}},\"required\":[\"path\"]}";
    }

    @Override
    public String execute(String paramsJson) {
        JsonObject params = JsonUtil.fromJson(paramsJson, JsonObject.class);
        String path = params.get("path").getAsString();
        File dir = new File(path);
        if (!dir.isDirectory()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Not a directory: " + path);
            return JsonUtil.toJson(error);
        }
        String[] entries = dir.list();
        if (entries == null) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Cannot list directory: " + path);
            return JsonUtil.toJson(error);
        }
        JsonArray files = new JsonArray();
        for (String entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("name", entry);
            item.addProperty("is_directory", new File(dir, entry).isDirectory());
            files.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("files", files);
        return JsonUtil.toJson(result);
    }
}
