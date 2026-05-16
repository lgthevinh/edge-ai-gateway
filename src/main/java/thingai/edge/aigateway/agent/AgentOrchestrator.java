package thingai.edge.aigateway.agent;

import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.message.MessageRole;
import thingai.edge.aigateway.llm.message.ToolCall;
import thingai.edge.aigateway.llm.response.Response;
import thingai.edge.aigateway.llm.response.ResponseStreamCallback;
import thingai.edge.aigateway.agent.session.Session;
import thingai.edge.aigateway.agent.session.SessionMessage;
import thingai.edge.aigateway.utils.JsonUtil;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Drives the agent turn loop: calls the agent repeatedly until it signals done
 * (finish_reason == "stop") or the safety cap (maxTurns) is reached.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Session history load + persist</li>
 *   <li>Turn loop with maxTurns safety cap</li>
 *   <li>Tool execution between turns</li>
 *   <li>Callback notifications (onTurn, onToken, onComplete, onError)</li>
 * </ul>
 *
 * <p>The {@link Agent} itself is a thin single-LLM-call unit; it does not loop.
 */
public class AgentOrchestrator {
    private static final String TAG = "AgentOrchestrator";
    private static final int DEFAULT_MAX_TURNS = 50;

    private final Agent[] agents;
    private final Dao dao;
    private final int maxTurns;

    public AgentOrchestrator(Dao dao, Agent... agents) {
        this(dao, DEFAULT_MAX_TURNS, agents);
    }

    public AgentOrchestrator(Dao dao, int maxTurns, Agent... agents) {
        this.dao = Objects.requireNonNull(dao, "dao must not be null");
        if (agents == null || agents.length == 0) throw new IllegalArgumentException("agents must not be empty");
        for (Agent agent : agents) Objects.requireNonNull(agent, "agents must not contain null");
        this.agents = Arrays.copyOf(agents, agents.length);
        this.maxTurns = maxTurns;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Blocking — runs the full turn loop and returns the final text. */
    public String run(String sessionId, String userInput) {
        SessionMessage[] historyRows = loadHistoryRows(sessionId);
        Message[] history = toMessages(historyRows);
        String finalText = runChain(sessionId, userInput, history, historyRows.length, true, null);
        return finalText;
    }

    /** Blocking — runs with caller-supplied history and does not persist session messages. */
    public String runWithHistory(String userInput, Message[] history) {
        return runChain(null, userInput, history != null ? history : new Message[0], 0, false, null);
    }

    /**
     * Async — runs intermediate turns blocking, streams the final answer token-by-token.
     * Returns a future that completes with the full final text.
     */
    public CompletableFuture<String> runAsync(String sessionId, String userInput, AgentChainCallback callback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SessionMessage[] historyRows = loadHistoryRows(sessionId);
                Message[] history = toMessages(historyRows);
                String finalText = runChain(sessionId, userInput, history, historyRows.length, true, callback);
                return finalText;
            } catch (CompletionException e) {
                throw e;
            } catch (Exception e) {
                if (callback != null) callback.onError(e);
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async — runs with caller-supplied history, streams the final answer, and does not
     * load or persist session messages through the DAO.
     */
    public CompletableFuture<String> runAsyncWithHistory(String userInput, Message[] history, AgentChainCallback callback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return runChain(null, userInput, history != null ? history : new Message[0], 0, false, callback);
            } catch (CompletionException e) {
                throw e;
            } catch (Exception e) {
                if (callback != null) callback.onError(e);
                throw new CompletionException(e);
            }
        });
    }

    public SessionMessage[] getHistoryRows(String sessionId) {
        return loadHistoryRows(sessionId);
    }

    public Session[] getSessions() {
        Session[] sessions = dao.readAll(Session.class);
        if (sessions != null) {
            Arrays.sort(sessions, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        }
        return sessions != null ? sessions : new Session[0];
    }

    public void deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        dao.deleteByColumn(SessionMessage.class, "session_id", sessionId);
        dao.deleteByColumn(Session.class, "session_id", sessionId);
    }

    // -------------------------------------------------------------------------
    // Chain: one turn loop per agent (single-agent = one loop)
    // -------------------------------------------------------------------------

    private String runChain(String sessionId, String userInput, Message[] history,
                            int historyLength, boolean persistSession, AgentChainCallback callback) {
        // Single-agent shortcut — most common case
        if (agents.length == 1) {
            Message[] messages = agents[0].buildMessages(history, userInput);
            TurnResult result = runTurnLoop(agents[0], messages, callback);
            String finalText = result.finalText();
            if (persistSession && finalText != null) {
                persistMessages(sessionId, historyLength, sliceNewMessages(result.messages(), history.length));
            }
            if (callback != null) callback.onComplete(finalText);
            return finalText;
        }

        // Multi-agent chain: each agent runs its own turn loop; output feeds into next agent
        Message[] context = history;
        String finalText = null;

        for (int i = 0; i < agents.length; i++) {
            Agent agent = agents[i];
            Message[] messages = agent.buildMessages(context, userInput);
            String agentOutput = runTurnLoopBlocking(agent, messages);
            if (agentOutput == null) {
                Exception e = new Exception("Agent " + agentName(i) + " returned null");
                if (callback != null) callback.onError(e);
                throw new CompletionException(e);
            }
            finalText = agentOutput;

            boolean isLast = (i == agents.length - 1);
            if (callback != null) {
                callback.onAgentComplete(i, agentName(i), agentOutput, agentOutput);
            }
            if (!isLast) {
                context = appendMessage(context, MessageRole.MODEL,
                        "Output from " + agentName(i) + ":\n" + agentOutput);
            }
        }

        if (persistSession) {
            persistMessages(sessionId, historyLength, new Message[] {
                    new Message(MessageRole.USER, userInput),
                    new Message(MessageRole.MODEL, finalText)
            });
        }
        if (callback != null) callback.onComplete(finalText);
        return finalText;
    }

    // -------------------------------------------------------------------------
    // Turn loop
    // -------------------------------------------------------------------------

    /**
     * Runs the turn loop for a single agent. When a callback is present each
     * LLM turn streams, and the structured stream result decides whether tools run.
     */
    private TurnResult runTurnLoop(Agent agent, Message[] messages, AgentChainCallback callback) {
        int turn = 0;
        Message[] current = messages;

        while (turn < maxTurns) {
            Response response;
            if (callback != null) {
                Message[] streamedMessages = current;
                try {
                    response = agent.callStreamResponse(streamedMessages, new ResponseStreamCallback() {
                        @Override public void onToken(String token) { callback.onToken(token); }
                        @Override public void onComplete(String fullText) { /* final event is emitted by runChain */ }
                        @Override public void onError(Exception e) { callback.onError(e); }
                        @Override public void onUsage(thingai.edge.aigateway.llm.response.ResponseUsage usage) { callback.onUsage(usage); }
                    }).join();
                } catch (Exception e) {
                    ILog.d(TAG, "Streaming failed: " + e.getMessage());
                    response = agent.call(current);
                }
            } else {
                response = agent.call(current);
            }

            if (response == null) {
                Exception e = new Exception("LLM returned null on turn " + turn);
                if (callback != null) callback.onError(e);
                throw new CompletionException(e);
            }

            if (isToolCall(response)) {
                // Agent decided to use tools — execute and loop
                String[] toolsUsed = extractToolNames(response);
                ILog.d(TAG, "[" + agent.getName() + "] turn " + turn + " — tools: " + Arrays.toString(toolsUsed));
                if (callback != null) callback.onTurn(turn, agent.getName(), toolsUsed);

                current = applyToolResults(agent, response, current);
                turn++;
                continue;
            }

            // Agent decided to stop — deliver final answer
            if (callback != null && response.getUsage() != null) callback.onUsage(response.getUsage());
            Message finalMessage = response.getChoices()[0].getMessage();
            Message[] completed = appendMessage(current, finalMessage);
            return new TurnResult(response.getMessageContent(), completed);
        }

        // Safety cap reached
        ILog.d(TAG, "[" + agent.getName() + "] maxTurns (" + maxTurns + ") reached — returning last response");
        Response last = agent.call(current);
        if (last == null) return new TurnResult(null, current);
        Message finalMessage = last.getChoices()[0].getMessage();
        return new TurnResult(last.getMessageContent(), appendMessage(current, finalMessage));
    }

    /** Blocking-only variant used for intermediate agents in a multi-agent chain. */
    private String runTurnLoopBlocking(Agent agent, Message[] messages) {
        int turn = 0;
        Message[] current = messages;

        while (turn < maxTurns) {
            Response response = agent.call(current);
            if (response == null) return null;

            if (isToolCall(response)) {
                current = applyToolResults(agent, response, current);
                turn++;
            } else {
                return response.getMessageContent();
            }
        }

        ILog.d(TAG, "[" + agent.getName() + "] maxTurns (" + maxTurns + ") reached");
        Response last = agent.call(current);
        return last != null ? last.getMessageContent() : null;
    }

    // -------------------------------------------------------------------------
    // Tool execution
    // -------------------------------------------------------------------------

    /**
     * Executes all tool calls from the response and returns an updated message array
     * with the assistant message + tool result messages appended.
     */
    private Message[] applyToolResults(Agent agent, Response response, Message[] current) {
        Message assistantMsg = response.getChoices()[0].getMessage();
        ToolCall[] toolCalls = assistantMsg.getToolCalls();
        if (toolCalls == null) return current;

        Message[] toolResults = new Message[toolCalls.length];
        for (int i = 0; i < toolCalls.length; i++) {
            ToolCall tc = toolCalls[i];
            String result = agent.executeTool(tc);
            Message toolMsg = new Message(MessageRole.TOOL, result);
            toolMsg.setToolCallId(tc.getId());
            toolResults[i] = toolMsg;
        }

        Message[] updated = new Message[current.length + 1 + toolResults.length];
        System.arraycopy(current, 0, updated, 0, current.length);
        updated[current.length] = assistantMsg;
        System.arraycopy(toolResults, 0, updated, current.length + 1, toolResults.length);
        return updated;
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private SessionMessage[] loadHistoryRows(String sessionId) {
        SessionMessage[] rows = dao.query(SessionMessage.class, "session_id", sessionId);
        if (rows != null) {
            Arrays.sort(rows, (a, b) -> Integer.compare(a.sequence, b.sequence));
        }
        return rows != null ? rows : new SessionMessage[0];
    }

    private void persistMessages(String sessionId, int historyLength, Message[] messages) {
        if (sessionId == null || sessionId.isBlank() || messages == null || messages.length == 0) return;
        persistSession(sessionId);

        int sequence = historyLength;
        long now = System.currentTimeMillis();

        for (Message message : messages) {
            if (message == null || MessageRole.SYSTEM.equals(message.getRole())) continue;
            dao.insertOrUpdate(new SessionMessage(
                    UUID.randomUUID().toString(),
                    sessionId,
                    sequence++,
                    message.getRole(),
                    message.getContent(),
                    message.getToolCalls() != null ? JsonUtil.toJson(message.getToolCalls()) : null,
                    message.getToolCallId(),
                    now
            ));
        }
    }

    private void persistSession(String sessionId) {
        long now = System.currentTimeMillis();
        Session[] existing = dao.query(Session.class, "session_id", sessionId);
        if (existing != null && existing.length > 0) {
            Session session = existing[0];
            session.updatedAt = now;
            dao.insertOrUpdate(session);
            return;
        }
        dao.insertOrUpdate(new Session(
                sessionId,
                agents.length > 0 ? agentName(0) : "assistant",
                now,
                now,
                agents.length > 0 ? agents[0].getTemperature() : 0.7,
                0,
                0
        ));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Message[] toMessages(SessionMessage[] rows) {
        if (rows == null || rows.length == 0) return new Message[0];
        Message[] messages = new Message[rows.length];
        for (int i = 0; i < rows.length; i++) {
            SessionMessage row = rows[i];
            Message msg = new Message(row.role, row.content);
            if (row.toolCallsJson != null) msg.setToolCalls(JsonUtil.fromJson(row.toolCallsJson, ToolCall[].class));
            if (row.toolCallId != null) msg.setToolCallId(row.toolCallId);
            messages[i] = msg;
        }
        return messages;
    }

    private Message[] appendMessage(Message[] messages, String role, String content) {
        Message[] updated = Arrays.copyOf(messages, messages.length + 1);
        updated[messages.length] = new Message(role, content);
        return updated;
    }

    private Message[] appendMessage(Message[] messages, Message message) {
        Message[] updated = Arrays.copyOf(messages, messages.length + 1);
        updated[messages.length] = message;
        return updated;
    }

    private Message[] sliceNewMessages(Message[] messages, int historyLength) {
        if (messages == null || messages.length == 0) return new Message[0];
        int start = Math.min(messages.length, 1 + historyLength);
        return Arrays.copyOfRange(messages, start, messages.length);
    }

    private String agentName(int index) {
        String name = agents[index].getName();
        return name == null || name.isBlank() ? "agent-" + index : name;
    }

    private boolean isToolCall(Response response) {
        return response.getChoices() != null
                && response.getChoices().length > 0
                && "tool_calls".equals(response.getChoices()[0].getFinishReason());
    }

    private String[] extractToolNames(Response response) {
        ToolCall[] tcs = response.getChoices()[0].getMessage().getToolCalls();
        if (tcs == null) return new String[0];
        String[] names = new String[tcs.length];
        for (int i = 0; i < tcs.length; i++) names[i] = tcs[i].getFunction().getName();
        return names;
    }

    private record TurnResult(String finalText, Message[] messages) {}
}
