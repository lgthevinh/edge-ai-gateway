package thingai.edge.aigateway.agent;

import org.thingai.base.dao.Dao;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.message.MessageRole;
import thingai.edge.aigateway.llm.message.ToolCall;
import thingai.edge.aigateway.session.SessionMessage;
import thingai.edge.aigateway.utils.JsonUtil;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class AgentOrchestrator {
    private final Agent[] agents;
    private final Dao dao;

    public AgentOrchestrator(Dao dao, Agent... agents) {
        this.dao = Objects.requireNonNull(dao, "dao must not be null");
        if (agents == null || agents.length == 0) {
            throw new IllegalArgumentException("agents must not be empty");
        }
        for (Agent agent : agents) {
            Objects.requireNonNull(agent, "agents must not contain null");
        }
        this.agents = Arrays.copyOf(agents, agents.length);
    }

    public String run(String sessionId, String userInput) {
        return runChain(sessionId, userInput, null);
    }

    public CompletableFuture<String> runAsync(String sessionId, String userInput, AgentChainCallback callback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String finalText = runChain(sessionId, userInput, callback);
                if (callback != null) callback.onComplete(finalText);
                return finalText;
            } catch (Exception e) {
                if (callback != null) callback.onError(e);
                throw new CompletionException(e);
            }
        });
    }

    private String runChain(String sessionId, String userInput, AgentChainCallback callback) {
        Message[] history = loadHistory(sessionId);
        Message[] context = history;
        String finalText = null;

        for (int i = 0; i < agents.length; i++) {
            finalText = agents[i].run(context, userInput);
            if (finalText == null) return null;

            String agentName = agentName(i);
            if (callback != null) callback.onAgentComplete(i, agentName, finalText, extractUserDisplay(finalText));
            context = appendMessage(context, MessageRole.MODEL, "Output from " + agentName + ":\n" + finalText);
        }

        persistMessages(sessionId, userInput, finalText);
        return finalText;
    }

    // --- persistence ---

    private Message[] loadHistory(String sessionId) {
        SessionMessage[] rows = dao.query(SessionMessage.class, "session_id = ?", sessionId);
        if (rows != null) {
            Arrays.sort(rows, (left, right) -> Integer.compare(left.sequence, right.sequence));
        }
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
        if (rows == null || rows.length == 0) return 0;
        int max = -1;
        for (SessionMessage row : rows) {
            if (row.sequence > max) max = row.sequence;
        }
        return max + 1;
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

    private Message[] appendMessage(Message[] messages, String role, String content) {
        Message[] current = messages == null ? new Message[0] : messages;
        Message[] updated = Arrays.copyOf(current, current.length + 1);
        updated[current.length] = new Message(role, content);
        return updated;
    }

    private String agentName(int index) {
        String name = agents[index].getName();
        return name == null || name.isBlank() ? "agent-" + index : name;
    }

    private String extractUserDisplay(String content) {
        if (content == null) return null;

        String openTag = "<user_display>";
        String closeTag = "</user_display>";
        int open = content.indexOf(openTag);
        int close = content.indexOf(closeTag);
        if (open < 0 || close < 0 || close <= open) return content;

        int start = open + openTag.length();
        return content.substring(start, close).trim();
    }
}
