package thingai.edge.aigateway.agent;

import com.google.gson.JsonObject;
import thingai.edge.aigateway.llm.content.Tool;
import thingai.edge.aigateway.llm.content.ToolFunction;
import thingai.edge.aigateway.utils.JsonUtil;

public interface AgentTool {
    String getName();
    String getDescription();
    String getParametersJson();
    String execute(String paramsJson);

    default Tool toTool() {
        JsonObject parameters = JsonUtil.fromJson(getParametersJson(), JsonObject.class);
        return new Tool(new ToolFunction(getName(), getDescription(), parameters));
    }
}
