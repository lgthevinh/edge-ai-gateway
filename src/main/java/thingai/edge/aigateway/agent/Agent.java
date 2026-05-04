package thingai.edge.aigateway.agent;

import org.thingai.base.log.ILog;
import thingai.edge.aigateway.llm.LlmProvider;
import thingai.edge.aigateway.llm.content.Content;
import thingai.edge.aigateway.llm.content.Tool;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.message.MessageRole;
import thingai.edge.aigateway.llm.message.ToolCall;
import thingai.edge.aigateway.llm.response.Response;
import thingai.edge.aigateway.llm.response.ResponseStreamCallback;

import java.util.concurrent.CompletableFuture;

/**
 * A thin single-LLM-call unit. Does NOT loop or manage tool execution.
 * The {@link AgentOrchestrator} owns the turn loop and calls this agent repeatedly.
 */
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

    /**
     * Builds the full message list: system instruction prepended, user message appended.
     */
    public Message[] buildMessages(Message[] history, String userInput) {
        Message systemMsg = new Message(MessageRole.SYSTEM, systemInstruction);
        Message[] withSystem = prepend(systemMsg, history);
        Message userMsg = new Message(MessageRole.USER, userInput);
        return append(withSystem, userMsg);
    }

    /**
     * Single blocking LLM call. Returns the raw {@link Response} — no looping.
     * The orchestrator inspects {@code finish_reason} and decides what to do next.
     */
    public Response call(Message[] messages) {
        Content content = buildContent(messages);
        return llmProvider.chatCompletion(content);
    }

    /**
     * Single streaming LLM call. Used by the orchestrator for the final answer turn.
     * Returns a future that completes with the full accumulated text.
     */
    public CompletableFuture<String> callStream(Message[] messages, ResponseStreamCallback callback) {
        Content content = buildContent(messages);
        CompletableFuture<String> result = new CompletableFuture<>();
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
        return result;
    }

    /**
     * Executes a single tool call by name. Returns JSON result string.
     */
    public String executeTool(ToolCall tc) {
        IAgentTool tool = findTool(tc.getFunction().getName());
        if (tool == null) {
            ILog.d(TAG, "[" + name + "] Tool not found: " + tc.getFunction().getName());
            return "{\"error\": \"tool not found: " + tc.getFunction().getName() + "\"}";
        }
        try {
            return tool.execute(tc.getFunction().getArguments());
        } catch (Exception e) {
            ILog.d(TAG, "[" + name + "] Tool execution error: " + e.getMessage());
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // --- private helpers ---

    private Content buildContent(Message[] messages) {
        Content content = new Content();
        content.setMessages(messages);
        content.setTemperature(temperature);
        if (tools != null && tools.length > 0) {
            Tool[] toolDefs = new Tool[tools.length];
            for (int i = 0; i < tools.length; i++) toolDefs[i] = tools[i].toTool();
            content.setTools(toolDefs);
        }
        return content;
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

    private Message[] append(Message[] base, Message last) {
        Message[] result = new Message[base.length + 1];
        System.arraycopy(base, 0, result, 0, base.length);
        result[base.length] = last;
        return result;
    }

    // --- Builder ---

    public static class Builder {
        private String name;
        private String systemInstruction;
        private LlmProvider llmProvider;
        private IAgentTool[] tools;
        private double temperature = 0.7;

        public Builder name(String name) { this.name = name; return this; }
        public Builder systemInstruction(String s) { this.systemInstruction = s; return this; }
        public Builder llmProvider(LlmProvider p) { this.llmProvider = p; return this; }
        public Builder tools(IAgentTool... tools) { this.tools = tools; return this; }
        public Builder temperature(double t) { this.temperature = t; return this; }

        public Agent build() { return new Agent(this); }
    }
}
