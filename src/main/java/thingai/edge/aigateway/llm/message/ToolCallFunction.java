package thingai.edge.aigateway.llm.message;

public class ToolCallFunction {
    private String name;
    private String arguments;

    public ToolCallFunction() {
    }

    public ToolCallFunction(String name, String arguments) {
        this.name = name;
        this.arguments = arguments;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }
}
