package thingai.edge.aigateway.agent;

import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.llm.content.Content;
import thingai.edge.aigateway.llm.content.Tool;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.message.MessageRole;
import thingai.edge.aigateway.llm.message.ToolCall;
import thingai.edge.aigateway.llm.response.Response;
import thingai.edge.aigateway.llm.response.ResponseChoice;
import thingai.edge.aigateway.llm.response.ResponseStreamCallback;
import thingai.edge.aigateway.session.SessionMessage;
import thingai.edge.aigateway.utils.JsonUtil;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AgentRunner {
    private static final String TAG = "AgentRunner";

    private final IAgent agent;
    private final Dao dao;

    public AgentRunner(IAgent agent, Dao dao) {
        this.agent = agent;
        this.dao = dao;
    }

    public String run(String sessionId, String userInput) {
        Content content = buildContent(sessionId, userInput);
        Response response = runLoop(content);
        if (response == null) return null;
        String result = response.getMessageContent();
        persistMessages(sessionId, userInput, content, response);
        return result;
    }

    public CompletableFuture<String> runAsync(String sessionId, String userInput, ResponseStreamCallback callback) {
        Content content = buildContent(sessionId, userInput);
        CompletableFuture<String> result = new CompletableFuture<>();
        runLoopAsync(content, callback, result, userInput, sessionId, content);
        return result;
    }

    // --- private ---

    private Content buildContent(String sessionId, String userInput) {
        SessionMessage[] rows = dao.query(SessionMessage.class, "session_id = ?", sessionId);
        Message[] history = toMessages(rows);
        Message systemMsg = new Message(MessageRole.SYSTEM, agent.getSystemInstruction());
        Message[] fullHistory = prepend(systemMsg, history);
        Content content = new Content(fullHistory, userInput, agent.getTemperature());

        IAgentTool[] agentTools = agent.getTools();
        if (agentTools != null && agentTools.length > 0) {
            Tool[] tools = new Tool[agentTools.length];
            for (int i = 0; i < agentTools.length; i++) tools[i] = agentTools[i].toTool();
            content.setTools(tools);
        }
        return content;
    }

    private Response runLoop(Content content) {
        Response response = agent.getLlmProvider().chatCompletion(content);
        while (response != null && isToolCall(response)) {
            content = handleToolCalls(response, content);
            if (content == null) break;
            response = agent.getLlmProvider().chatCompletion(content);
        }
        return response;
    }

    private void runLoopAsync(Content content, ResponseStreamCallback callback,
                               CompletableFuture<String> result,
                               String userInput, String sessionId, Content originalContent) {
        agent.getLlmProvider().chatCompletionAsync(content, new ResponseStreamCallback() {
            @Override
            public void onToken(String token) {
                callback.onToken(token);
            }

            @Override
            public void onComplete(String fullText) {
                // check if this was a tool call response (non-streaming tool calls arrive via onComplete)
                // for simplicity, treat final text as stop — tool calls in streaming require delta buffering
                callback.onComplete(fullText);
                result.complete(fullText);
                persistMessages(sessionId, userInput, originalContent,
                        buildStopResponse(fullText));
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

        // build new messages array: existing + assistant + tool results
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

    private void persistMessages(String sessionId, String userInput, Content content, Response response) {
        int sequence = nextSequence(sessionId);
        long now = System.currentTimeMillis();

        // user message
        SessionMessage userMsg = new SessionMessage(
                UUID.randomUUID().toString(), sessionId, sequence++,
                MessageRole.USER, userInput, null, null, now);
        dao.insertOrUpdate(userMsg);

        // assistant message
        if (response != null && response.getChoices() != null && response.getChoices().length > 0) {
            ResponseChoice choice = response.getChoices()[0];
            Message assistantMsg = choice.getMessage();
            String toolCallsJson = assistantMsg.getToolCalls() != null
                    ? JsonUtil.toJson(assistantMsg.getToolCalls()) : null;
            SessionMessage assistantRecord = new SessionMessage(
                    UUID.randomUUID().toString(), sessionId, sequence,
                    MessageRole.MODEL, assistantMsg.getContent(), toolCallsJson, null, now);
            dao.insertOrUpdate(assistantRecord);
        }
    }

    private int nextSequence(String sessionId) {
        SessionMessage[] rows = dao.query(SessionMessage.class, "session_id = ?", sessionId);
        return rows == null ? 0 : rows.length;
    }

    private Message[] toMessages(SessionMessage[] rows) {
        if (rows == null || rows.length == 0) return new Message[0];
        Message[] messages = new Message[rows.length];
        for (int i = 0; i < rows.length; i++) {
            SessionMessage row = rows[i];
            Message msg = new Message(row.role, row.content);
            if (row.toolCallsJson != null) {
                msg.setToolCalls(JsonUtil.fromJson(row.toolCallsJson, ToolCall[].class));
            }
            if (row.toolCallId != null) {
                msg.setToolCallId(row.toolCallId);
            }
            messages[i] = msg;
        }
        return messages;
    }

    private IAgentTool findTool(String name) {
        IAgentTool[] tools = agent.getTools();
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

    private Response buildStopResponse(String fullText) {
        Message msg = new Message(MessageRole.MODEL, fullText);
        return new Response(new ResponseChoice[]{new ResponseChoice(msg, "stop")}, null);
    }
}
