package thingai.edge.aigateway.llm.content;

import com.google.gson.JsonObject;

public class ToolFunction {
    private String name;
    private String description;
    private JsonObject parameters;

    public ToolFunction() {
    }

    public ToolFunction(String name, String description, JsonObject parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonObject getParameters() {
        return parameters;
    }

    public void setParameters(JsonObject parameters) {
        this.parameters = parameters;
    }
}
