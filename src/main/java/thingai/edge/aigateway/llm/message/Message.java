package thingai.edge.aigateway.llm.message;

import com.google.gson.annotations.SerializedName;

public class Message {
    private String role;
    private String content;

    @SerializedName("tool_calls")
    private ToolCall[] toolCalls;

    @SerializedName("tool_call_id")
    private String toolCallId;

    public Message() {

    }

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public ToolCall[] getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(ToolCall[] toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }
}
