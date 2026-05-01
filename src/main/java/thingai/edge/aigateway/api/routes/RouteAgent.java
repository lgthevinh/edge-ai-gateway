package thingai.edge.aigateway.api.routes;

import com.google.gson.JsonObject;
import io.javalin.apibuilder.EndpointGroup;
import io.javalin.http.sse.SseClient;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.agent.AgentRunner;
import thingai.edge.aigateway.agent.IAgent;
import thingai.edge.aigateway.llm.response.ResponseStreamCallback;
import thingai.edge.aigateway.utils.JsonUtil;

import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;

public class RouteAgent implements EndpointGroup {
    private static final String TAG = "RouteAgent";

    private final IAgent agent;
    private final Dao dao;

    public RouteAgent(IAgent agent, Dao dao) {
        this.agent = agent;
        this.dao = dao;
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

                AgentRunner runner = new AgentRunner(agent, dao);
                String reply = runner.run(sessionId, message);
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

            AgentRunner runner = new AgentRunner(agent, dao);
            CountDownLatch done = new CountDownLatch(1);

            client.onClose(done::countDown);

            runner.runAsync(sessionId, message, new ResponseStreamCallback() {
                @Override
                public void onToken(String token) {
                    if (!client.terminated()) {
                        JsonObject chunk = new JsonObject();
                        chunk.addProperty("token", token);
                        client.sendEvent("token", JsonUtil.toJson(chunk));
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
