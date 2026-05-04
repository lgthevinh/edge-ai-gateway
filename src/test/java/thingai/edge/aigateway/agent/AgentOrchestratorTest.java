package thingai.edge.aigateway.agent;

import org.junit.jupiter.api.Test;
import org.thingai.base.dao.Dao;
import org.thingai.base.dao.Migration;
import thingai.edge.aigateway.llm.LlmProvider;
import thingai.edge.aigateway.llm.content.Content;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.message.MessageRole;
import thingai.edge.aigateway.llm.response.Response;
import thingai.edge.aigateway.llm.response.ResponseChoice;
import thingai.edge.aigateway.llm.response.ResponseStreamCallback;
import thingai.edge.aigateway.session.SessionMessage;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestratorTest {

    @Test
    void runPassesOriginalUserInputToEveryAgentAndAddsPreviousOutputsToContext() {
        MemoryDao dao = new MemoryDao();
        dao.messages.add(new SessionMessage("old-2", "s1", 2, MessageRole.MODEL, "older reply", null, null, 1));
        dao.messages.add(new SessionMessage("old-1", "s1", 1, MessageRole.USER, "older user", null, null, 1));

        CapturingProvider firstProvider = new CapturingProvider("research notes");
        CapturingProvider secondProvider = new CapturingProvider("final answer");
        Agent first = agent("researcher", firstProvider);
        Agent second = agent("assistant", secondProvider);

        AgentOrchestrator orchestrator = new AgentOrchestrator(dao, new Agent[]{first, second});

        String result = orchestrator.run("s1", "new user question");

        assertEquals("final answer", result);
        assertEquals("new user question", lastMessage(firstProvider.lastContent).getContent());
        assertEquals("new user question", lastMessage(secondProvider.lastContent).getContent());
        assertTrue(containsContent(secondProvider.lastContent, "Output from researcher:\nresearch notes"));
        assertEquals(MessageRole.USER, dao.messages.get(2).role);
        assertEquals("new user question", dao.messages.get(2).content);
        assertEquals(MessageRole.MODEL, dao.messages.get(3).role);
        assertEquals("final answer", dao.messages.get(3).content);
        assertEquals(3, dao.messages.get(2).sequence);
        assertEquals(4, dao.messages.get(3).sequence);
    }

    @Test
    void runAsyncUsesBlockingChainAndReportsEachAgentResult() throws Exception {
        MemoryDao dao = new MemoryDao();
        Agent first = agent("one", new CapturingProvider("<agent_state>\nDECISION: needs_research\n</agent_state>\n\n<user_display>\nChecking the request.\n</user_display>"));
        Agent second = agent("two", new CapturingProvider("second result"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(dao, new Agent[]{first, second});
        RecordingChainCallback callback = new RecordingChainCallback();

        String result = orchestrator.runAsync("s1", "question", callback).get(5, TimeUnit.SECONDS);

        assertEquals("second result", result);
        assertEquals(List.of("0:one:Checking the request.", "1:two:second result"), callback.agentDisplays);
        assertEquals("second result", callback.finalText);
    }

    @Test
    void constructorRejectsInvalidAgents() {
        MemoryDao dao = new MemoryDao();

        assertThrows(IllegalArgumentException.class, () -> new AgentOrchestrator(dao, new Agent[0]));
        assertThrows(NullPointerException.class, () -> new AgentOrchestrator(dao, new Agent[]{null}));
    }

    private static Agent agent(String name, LlmProvider provider) {
        return new Agent.Builder()
                .name(name)
                .systemInstruction("system")
                .llmProvider(provider)
                .build();
    }

    private static Message lastMessage(Content content) {
        Message[] messages = content.getMessages();
        return messages[messages.length - 1];
    }

    private static boolean containsContent(Content content, String value) {
        for (Message message : content.getMessages()) {
            if (value.equals(message.getContent())) return true;
        }
        return false;
    }

    private static class CapturingProvider extends LlmProvider {
        private final String reply;
        private Content lastContent;

        private CapturingProvider(String reply) {
            super("http://localhost");
            this.reply = reply;
        }

        @Override
        public Response chatCompletion(Content content) {
            lastContent = content;
            return new Response(new ResponseChoice[]{
                    new ResponseChoice(new Message(MessageRole.MODEL, reply), "stop")
            }, null);
        }

        @Override
        public CompletableFuture<Response> chatCompletionAsync(Content content, ResponseStreamCallback callback) {
            Response response = chatCompletion(content);
            callback.onComplete(reply);
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public boolean healthCheck() {
            return true;
        }
    }

    private static class RecordingChainCallback implements AgentChainCallback {
        private final List<String> agentDisplays = new ArrayList<>();
        private String finalText;

        @Override
        public void onToken(String token) {

        }

        @Override
        public void onAgentComplete(int index, String agentName, String content, String display) {
            agentDisplays.add(index + ":" + agentName + ":" + display);
        }

        @Override
        public void onComplete(String finalText) {
            this.finalText = finalText;
        }

        @Override
        public void onError(Exception e) {
        }
    }

    private static class MemoryDao implements Dao {
        private final List<SessionMessage> messages = new ArrayList<>();

        @Override
        public void initDao(Class[] classes) {
        }

        @Override
        public void initDao(Class[] classes, Migration... migrations) {
        }

        @Override
        public <T> T[] readAll(Class<T> cls) {
            return emptyArray(cls);
        }

        @Override
        public <T> void insertOrUpdate(T entity) {
            if (entity instanceof SessionMessage) {
                messages.add((SessionMessage) entity);
            }
        }

        @Override
        public <T> void insertOrUpdate(Class<T> cls, T entity) {
            insertOrUpdate(entity);
        }

        @Override
        public <T> void insertBatch(T[] entities) {
            for (T entity : entities) insertOrUpdate(entity);
        }

        @Override
        public <T, K> void delete(Class<T> cls, K key) {
        }

        @Override
        public <T> void delete(T entity) {
        }

        @Override
        public <T> void deleteByColumn(Class<T> cls, String column, String value) {
        }

        @Override
        public <T> void deleteAll(Class<T> cls) {
        }

        @Override
        public <T> T[] query(Class<T> cls, String where, String value) {
            if (cls == SessionMessage.class && "session_id = ?".equals(where)) {
                List<SessionMessage> matches = new ArrayList<>();
                for (SessionMessage message : messages) {
                    if (value.equals(message.sessionId)) matches.add(message);
                }
                @SuppressWarnings("unchecked")
                T[] result = matches.toArray((T[]) Array.newInstance(cls, matches.size()));
                return result;
            }
            return emptyArray(cls);
        }

        @Override
        public <T> T[] query(Class<T> cls, String[] columns, String[] values) {
            return emptyArray(cls);
        }

        @Override
        public <T> T[] query(Class<T> cls, String where) {
            return emptyArray(cls);
        }

        @Override
        public Map<String, Object>[] queryRaw(String sql) {
            @SuppressWarnings("unchecked")
            Map<String, Object>[] result = new Map[0];
            return result;
        }

        @SuppressWarnings("unchecked")
        private <T> T[] emptyArray(Class<T> cls) {
            return (T[]) Array.newInstance(cls, 0);
        }
    }
}
