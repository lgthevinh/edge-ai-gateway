package thingai.edge.aigateway.llm.content;

public class Tool {
    private String type;
    private ToolFunction function;

    public Tool() {
    }

    public Tool(ToolFunction function) {
        this.type = "function";
        this.function = function;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public ToolFunction getFunction() {
        return function;
    }

    public void setFunction(ToolFunction function) {
        this.function = function;
    }
}
