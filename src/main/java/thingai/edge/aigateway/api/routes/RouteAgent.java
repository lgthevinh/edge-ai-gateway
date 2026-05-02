package thingai.edge.aigateway.api.routes;

import com.google.gson.JsonObject;
import io.javalin.apibuilder.EndpointGroup;
import io.javalin.http.sse.SseClient;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.agent.AgentChainCallback;
import thingai.edge.aigateway.agent.AgentOrchestrator;
import thingai.edge.aigateway.utils.JsonUtil;

import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;

public class RouteAgent implements EndpointGroup {
    private static final String TAG = "RouteAgent";

    private final AgentOrchestrator orchestrator;

    public RouteAgent(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

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

                String reply = orchestrator.run(sessionId, message);

                JsonObject response = new JsonObject();
                response.addProperty("reply", reply);
                ctx.json(JsonUtil.toJson(response));
            });
        });
    }

    public Consumer<SseClient> sseHandler() {
        return client -> {
            JsonObject params = JsonUtil.fromJson(client.ctx().queryParam("body"), JsonObject.class);
            if (params == null || !params.has("session_id") || !params.has("message")) {
                client.sendEvent("error", "{\"error\":\"session_id and message are required\"}");
                client.close();
                return;
            }

            String sessionId = params.get("session_id").getAsString();
            String message = params.get("message").getAsString();

            CountDownLatch done = new CountDownLatch(1);
            client.onClose(done::countDown);

            orchestrator.runAsync(sessionId, message, new AgentChainCallback() {
                @Override
                public void onAgentComplete(int index, String agentName, String content, String display) {
                    if (!client.terminated()) {
                        JsonObject event = new JsonObject();
                        event.addProperty("index", index);
                        event.addProperty("name", agentName);
                        event.addProperty("content", content);
                        event.addProperty("display", display);
                        client.sendEvent("agent", JsonUtil.toJson(event));
                    }
                }

                @Override
                public void onComplete(String fullText) {
                    if (!client.terminated()) {
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
            });

            try { done.await(); } catch (InterruptedException ignored) {}
        };
    }
}
