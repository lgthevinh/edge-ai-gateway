package thingai.edge.aigateway.agent;

import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.message.MessageRole;
import thingai.edge.aigateway.llm.message.ToolCall;
import thingai.edge.aigateway.llm.response.ResponseStreamCallback;
import thingai.edge.aigateway.session.SessionMessage;
import thingai.edge.aigateway.utils.JsonUtil;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AgentOrchestrator {
    private static final String TAG = "AgentOrchestrator";

    private final Agent[] agents;
    private final Dao dao;

    public AgentOrchestrator(Dao dao, Agent... agents) {
        this.dao = dao;
        this.agents = agents;
    }

    public String run(String sessionId, String userInput) {
        Message[] history = loadHistory(sessionId);
        String input = userInput;
        for (int i = 0; i < agents.length; i++) {
            Message[] ctx = (i == 0) ? history : new Message[0];
            input = agents[i].run(ctx, input);
            if (input == null) return null;
        }
        persistMessages(sessionId, userInput, input);
        return input;
    }

    public CompletableFuture<String> runAsync(String sessionId, String userInput, ResponseStreamCallback callback) {
        Message[] history = loadHistory(sessionId);
        CompletableFuture<String> result = new CompletableFuture<>();
        runChainAsync(0, history, userInput, callback, result, sessionId, userInput);
        return result;
    }

    private void runChainAsync(int index, Message[] history, String input,
                               ResponseStreamCallback callback, CompletableFuture<String> result,
                               String sessionId, String originalInput) {
        boolean isLast = (index == agents.length - 1);
        Message[] ctx = (index == 0) ? history : new Message[0];

        agents[index].runAsync(ctx, input, new ResponseStreamCallback() {
            @Override
            public void onToken(String token) {
                callback.onToken(token);
            }

            @Override
            public void onComplete(String fullText) {
                if (isLast) {
                    persistMessages(sessionId, originalInput, fullText);
                    callback.onComplete(fullText);
                    result.complete(fullText);
                } else {
                    runChainAsync(index + 1, null, fullText, callback, result, sessionId, originalInput);
                }
            }

            @Override
            public void onError(Exception e) {
                callback.onError(e);
                result.completeExceptionally(e);
            }
        });
    }

    // --- persistence ---

    private Message[] loadHistory(String sessionId) {
        SessionMessage[] rows = dao.query(SessionMessage.class, "session_id = ?", sessionId);
        return toMessages(rows);
    }

    private void persistMessages(String sessionId, String userInput, String reply) {
        int sequence = nextSequence(sessionId);
        long now = System.currentTimeMillis();

        SessionMessage userMsg = new SessionMessage(
                UUID.randomUUID().toString(), sessionId, sequence++,
                MessageRole.USER, userInput, null, null, now);
        dao.insertOrUpdate(userMsg);

        if (reply != null) {
            SessionMessage assistantMsg = new SessionMessage(
                    UUID.randomUUID().toString(), sessionId, sequence,
                    MessageRole.MODEL, reply, null, null, now);
            dao.insertOrUpdate(assistantMsg);
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
}
