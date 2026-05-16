package thingai.edge.aigateway.api.routes;

import com.google.gson.JsonObject;
import io.javalin.apibuilder.EndpointGroup;
import io.javalin.http.sse.SseClient;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.EdgeAiGateway;
import thingai.edge.aigateway.agent.AgentChainCallback;
import thingai.edge.aigateway.llm.response.ResponseUsage;
import thingai.edge.aigateway.agent.session.Session;
import thingai.edge.aigateway.agent.session.SessionMessage;
import thingai.edge.aigateway.utils.JsonUtil;

import com.google.gson.JsonArray;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.delete;
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
                        body.get("message").getAsString()
                ));

                JsonObject response = new JsonObject();
                response.addProperty("stream_id", streamId);
                ctx.json(JsonUtil.toJson(response));
            });
            get("/chat/history", ctx -> {
                String sessionId = ctx.queryParam("session_id");
                if (sessionId == null || sessionId.isBlank()) {
                    ctx.status(400).result("{\"error\":\"session_id is required\"}");
                    return;
                }

                JsonArray messages = new JsonArray();
                for (SessionMessage row : EdgeAiGateway.getAgentOrchestrator().getHistoryRows(sessionId)) {
                    JsonObject item = new JsonObject();
                    item.addProperty("message_id", row.messageId);
                    item.addProperty("session_id", row.sessionId);
                    item.addProperty("sequence", row.sequence);
                    item.addProperty("role", row.role);
                    item.addProperty("content", row.content != null ? row.content : "");
                    if (row.toolCallsJson != null && !row.toolCallsJson.isBlank()) {
                        item.add("tool_calls", JsonUtil.fromJson(row.toolCallsJson, JsonArray.class));
                    }
                    if (row.toolCallId != null) item.addProperty("tool_call_id", row.toolCallId);
                    item.addProperty("created_at", row.createdAt);
                    messages.add(item);
                }

                JsonObject response = new JsonObject();
                response.addProperty("session_id", sessionId);
                response.add("messages", messages);
                ctx.json(JsonUtil.toJson(response));
            });
            get("/chat/sessions", ctx -> {
                JsonArray sessions = new JsonArray();
                for (Session row : EdgeAiGateway.getAgentOrchestrator().getSessions()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("session_id", row.sessionId);
                    item.addProperty("agent_id", row.agentId);
                    item.addProperty("created_at", row.createdAt);
                    item.addProperty("updated_at", row.updatedAt);
                    sessions.add(item);
                }

                JsonObject response = new JsonObject();
                response.add("sessions", sessions);
                ctx.json(JsonUtil.toJson(response));
            });
            delete("/chat/sessions/{sessionId}", ctx -> {
                String sessionId = ctx.pathParam("sessionId");
                if (sessionId == null || sessionId.isBlank()) {
                    ctx.status(400).result("{\"error\":\"sessionId is required\"}");
                    return;
                }

                EdgeAiGateway.getAgentOrchestrator().deleteSession(sessionId);
                JsonObject response = new JsonObject();
                response.addProperty("deleted", true);
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

            EdgeAiGateway.getAgentOrchestrator().runAsync(request.sessionId, request.message, new AgentChainCallback() {
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
                params.get("message").getAsString()
        );
    }

    private record StreamRequest(String sessionId, String message) {}
}
