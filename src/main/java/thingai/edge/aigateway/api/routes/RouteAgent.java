package thingai.edge.aigateway.api.routes;

import com.google.gson.JsonObject;
import io.javalin.apibuilder.EndpointGroup;
import io.javalin.http.sse.SseClient;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.EdgeAiGateway;
import thingai.edge.aigateway.agent.AgentChainCallback;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.message.MessageRole;
import thingai.edge.aigateway.llm.response.ResponseUsage;
import thingai.edge.aigateway.utils.JsonUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;

public class RouteAgent implements EndpointGroup {
    private static final String TAG = "RouteAgent";
    private static final ConcurrentHashMap<String, StreamRequest> STREAM_REQUESTS = new ConcurrentHashMap<>();

    @Override
    public void addEndpoints() {
        path("agent", () -> {
            post("/chat", ctx -> {
                JsonObject body = JsonUtil.fromJson(ctx.body(), JsonObject.class);
                if (body == null || !body.has("session_id") || !body.has("message")) {
                    ctx.status(400).result("{\"error\":\"session_id and message are required\"}");
                    return;
                }
                String sessionId = body.get("session_id").getAsString();
                String message = body.get("message").getAsString();

                String reply = EdgeAiGateway.getAgentOrchestrator().run(sessionId, message);

                JsonObject response = new JsonObject();
                response.addProperty("reply", reply);
                ctx.json(JsonUtil.toJson(response));
            });
            post("/chat/stream/start", ctx -> {
                JsonObject body = JsonUtil.fromJson(ctx.body(), JsonObject.class);
                if (body == null || !body.has("session_id") || !body.has("message")) {
                    ctx.status(400).result("{\"error\":\"session_id and message are required\"}");
                    return;
                }

                String streamId = UUID.randomUUID().toString();
                STREAM_REQUESTS.put(streamId, new StreamRequest(
                        body.get("session_id").getAsString(),
                        body.get("message").getAsString(),
                        parseHistory(body)
                ));

                JsonObject response = new JsonObject();
                response.addProperty("stream_id", streamId);
                ctx.json(JsonUtil.toJson(response));
            });
        });
    }

    public Consumer<SseClient> sseHandler() {
        return client -> {
            StreamRequest request = resolveStreamRequest(client);
            if (request == null || request.sessionId == null || request.message == null) {
                client.sendEvent("error", "{\"error\":\"session_id and message are required\"}");
                client.close();
                return;
            }

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<ResponseUsage> latestUsage = new AtomicReference<>();
            client.onClose(done::countDown);

            EdgeAiGateway.getAgentOrchestrator().runAsyncWithHistory(request.message, request.history, new AgentChainCallback() {
                @Override
                public void onTurn(int turn, String agentName, String[] toolsUsed) {
                    if (!client.terminated()) {
                        JsonObject event = new JsonObject();
                        event.addProperty("turn", turn);
                        event.addProperty("agent", agentName);
                        com.google.gson.JsonArray tools = new com.google.gson.JsonArray();
                        for (String t : toolsUsed) tools.add(t);
                        event.add("tools", tools);
                        client.sendEvent("turn", JsonUtil.toJson(event));
                    }
                }

                @Override
                public void onToken(String token) {
                    if (!client.terminated()) {
                        JsonObject event = new JsonObject();
                        event.addProperty("token", token);
                        client.sendEvent("token", JsonUtil.toJson(event));
                    }
                }

                @Override
                public void onAgentComplete(int index, String agentName, String content, String display) {
                    if (!client.terminated()) {
                        JsonObject event = new JsonObject();
                        event.addProperty("index", index);
                        event.addProperty("name", agentName);
                        event.addProperty("display", display);
                        client.sendEvent("agent", JsonUtil.toJson(event));
                    }
                }

                @Override
                public void onComplete(String fullText) {
                    if (!client.terminated()) {
                        JsonObject event = new JsonObject();
                        event.addProperty("final_text", fullText != null ? fullText : "");
                        ResponseUsage usage = latestUsage.get();
                        if (usage != null) event.add("usage", JsonUtil.fromJson(JsonUtil.toJson(usage), JsonObject.class));
                        client.sendEvent("final", JsonUtil.toJson(event));
                        client.sendEvent("done", "{}");
                        client.close();
                    }
                    done.countDown();
                }

                @Override
                public void onError(Exception e) {
                    ILog.d(TAG, "SSE stream error: " + e.getMessage());
                    if (!client.terminated()) client.close();
                    done.countDown();
                }

                @Override
                public void onUsage(ResponseUsage usage) {
                    if (usage != null) latestUsage.set(usage);
                }
            });

            try { done.await(); } catch (InterruptedException ignored) {}
        };
    }

    private StreamRequest resolveStreamRequest(SseClient client) {
        String streamId = client.ctx().queryParam("stream_id");
        if (streamId != null && !streamId.isBlank()) {
            return STREAM_REQUESTS.remove(streamId);
        }

        JsonObject params = JsonUtil.fromJson(client.ctx().queryParam("body"), JsonObject.class);
        if (params == null || !params.has("session_id") || !params.has("message")) return null;
        return new StreamRequest(
                params.get("session_id").getAsString(),
                params.get("message").getAsString(),
                parseHistory(params)
        );
    }

    private Message[] parseHistory(JsonObject body) {
        if (body == null || !body.has("history") || !body.get("history").isJsonArray()) {
            return new Message[0];
        }

        JsonArray history = body.getAsJsonArray("history");
        java.util.ArrayList<Message> messages = new java.util.ArrayList<>();
        for (JsonElement element : history) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!item.has("role") || !item.has("content")) continue;

            String role = item.get("role").getAsString();
            String content = item.get("content").getAsString();
            if (content == null || content.isBlank()) continue;

            if (MessageRole.USER.equals(role)) {
                messages.add(new Message(MessageRole.USER, content));
            } else if (MessageRole.MODEL.equals(role) || "model".equals(role)) {
                messages.add(new Message(MessageRole.MODEL, content));
            }
        }
        return messages.toArray(new Message[0]);
    }

    private record StreamRequest(String sessionId, String message, Message[] history) {}
}
