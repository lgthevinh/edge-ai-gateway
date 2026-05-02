package thingai.edge.aigateway.agent;

import org.thingai.base.log.ILog;
import thingai.edge.aigateway.llm.LlmProvider;
import thingai.edge.aigateway.llm.content.Content;
import thingai.edge.aigateway.llm.content.Tool;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.message.MessageRole;
import thingai.edge.aigateway.llm.message.ToolCall;
import thingai.edge.aigateway.llm.response.Response;
import thingai.edge.aigateway.llm.response.ResponseChoice;
import thingai.edge.aigateway.llm.response.ResponseStreamCallback;

import java.util.concurrent.CompletableFuture;

public class Agent {
    private static final String TAG = "Agent";

    private final String name;
    private final String systemInstruction;
    private final LlmProvider llmProvider;
    private final IAgentTool[] tools;
    private final double temperature;

    private Agent(Builder builder) {
        this.name = builder.name;
        this.systemInstruction = builder.systemInstruction;
        this.llmProvider = builder.llmProvider;
        this.tools = builder.tools;
        this.temperature = builder.temperature;
    }

    public String getName() { return name; }
    public String getSystemInstruction() { return systemInstruction; }
    public LlmProvider getLlmProvider() { return llmProvider; }
    public IAgentTool[] getTools() { return tools; }
    public double getTemperature() { return temperature; }

    public String run(Message[] history, String userInput) {
        Content content = buildContent(history, userInput);
        Response response = runLoop(content);
        if (response == null) return null;
        return response.getMessageContent();
    }

    public CompletableFuture<String> runAsync(Message[] history, String userInput, ResponseStreamCallback callback) {
        Content content = buildContent(history, userInput);
        CompletableFuture<String> result = new CompletableFuture<>();
        runLoopAsync(content, callback, result);
        return result;
    }

    // --- private ---

    private Content buildContent(Message[] history, String userInput) {
        Message systemMsg = new Message(MessageRole.SYSTEM, systemInstruction);
        Message[] fullHistory = prepend(systemMsg, history);
        Content content = new Content(fullHistory, userInput, temperature);

        if (tools != null && tools.length > 0) {
            Tool[] toolDefs = new Tool[tools.length];
            for (int i = 0; i < tools.length; i++) toolDefs[i] = tools[i].toTool();
            content.setTools(toolDefs);
        }
        return content;
    }

    private Response runLoop(Content content) {
        Response response = llmProvider.chatCompletion(content);
        while (response != null && isToolCall(response)) {
            content = handleToolCalls(response, content);
            if (content == null) break;
            response = llmProvider.chatCompletion(content);
        }
        return response;
    }

    private void runLoopAsync(Content content, ResponseStreamCallback callback, CompletableFuture<String> result) {
        if (tools == null || tools.length == 0) {
            streamResponse(content, callback, result);
            return;
        }

        Response response = llmProvider.chatCompletion(content);
        while (response != null && isToolCall(response)) {
            content = handleToolCalls(response, content);
            if (content == null) {
                Exception e = new Exception("Tool call loop failed");
                callback.onError(e);
                result.completeExceptionally(e);
                return;
            }
            response = llmProvider.chatCompletion(content);
        }

        if (response == null) {
            Exception e = new Exception("LLM returned null");
            callback.onError(e);
            result.completeExceptionally(e);
            return;
        }

        String text = response.getMessageContent();
        callback.onComplete(text);
        result.complete(text);
    }

    private void streamResponse(Content content, ResponseStreamCallback callback, CompletableFuture<String> result) {
        llmProvider.chatCompletionAsync(content, new ResponseStreamCallback() {
            @Override
            public void onToken(String token) {
                callback.onToken(token);
            }

            @Override
            public void onComplete(String fullText) {
                callback.onComplete(fullText);
                result.complete(fullText);
            }

            @Override
            public void onError(Exception e) {
                callback.onError(e);
                result.completeExceptionally(e);
            }
        });
    }

    private Content handleToolCalls(Response response, Content content) {
        Message assistantMsg = response.getChoices()[0].getMessage();
        ToolCall[] toolCalls = assistantMsg.getToolCalls();
        if (toolCalls == null) return null;

        Message[] toolResults = new Message[toolCalls.length];
        for (int i = 0; i < toolCalls.length; i++) {
            ToolCall tc = toolCalls[i];
            String toolResult = executeToolCall(tc);
            Message toolMsg = new Message(MessageRole.TOOL, toolResult);
            toolMsg.setToolCallId(tc.getId());
            toolResults[i] = toolMsg;
        }

        Message[] current = content.getMessages();
        Message[] updated = new Message[current.length + 1 + toolResults.length];
        System.arraycopy(current, 0, updated, 0, current.length);
        updated[current.length] = assistantMsg;
        System.arraycopy(toolResults, 0, updated, current.length + 1, toolResults.length);

        Content next = new Content();
        next.setMessages(updated);
        next.setTemperature(content.getTemperature());
        next.setTools(content.getTools());
        return next;
    }

    private String executeToolCall(ToolCall tc) {
        IAgentTool tool = findTool(tc.getFunction().getName());
        if (tool == null) {
            ILog.d(TAG, "Tool not found: " + tc.getFunction().getName());
            return "{\"error\": \"tool not found\"}";
        }
        try {
            return tool.execute(tc.getFunction().getArguments());
        } catch (Exception e) {
            ILog.d(TAG, "Tool execution error: " + e.getMessage());
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private IAgentTool findTool(String name) {
        if (tools == null) return null;
        for (IAgentTool tool : tools) {
            if (tool.getName().equals(name)) return tool;
        }
        return null;
    }

    private Message[] prepend(Message first, Message[] rest) {
        Message[] result = new Message[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }

    private boolean isToolCall(Response response) {
        return response.getChoices() != null
                && response.getChoices().length > 0
                && "tool_calls".equals(response.getChoices()[0].getFinishReason());
    }

    // --- Builder ---

    public static class Builder {
        private String name;
        private String systemInstruction;
        private LlmProvider llmProvider;
        private IAgentTool[] tools;
        private double temperature = 0.7;

        public Builder name(String name) { this.name = name; return this; }
        public Builder systemInstruction(String systemInstruction) { this.systemInstruction = systemInstruction; return this; }
        public Builder llmProvider(LlmProvider llmProvider) { this.llmProvider = llmProvider; return this; }
        public Builder tools(IAgentTool... tools) { this.tools = tools; return this; }
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }

        public Agent build() { return new Agent(this); }
    }
}
