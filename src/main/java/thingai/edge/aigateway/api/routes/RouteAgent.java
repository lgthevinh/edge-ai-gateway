package thingai.edge.aigateway.api.routes;

import com.google.gson.JsonObject;
import io.javalin.apibuilder.EndpointGroup;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.agent.AgentRunner;
import thingai.edge.aigateway.agent.IAgent;
import thingai.edge.aigateway.llm.response.ResponseStreamCallback;
import thingai.edge.aigateway.utils.JsonUtil;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

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
                boolean stream = body.has("stream") && body.get("stream").getAsBoolean();

                AgentRunner runner = new AgentRunner(agent, dao);

                if (stream) {
                    ctx.contentType("text/event-stream");
                    PipedOutputStream out = new PipedOutputStream();
                    PipedInputStream in = new PipedInputStream(out);
                    ctx.result(in);

                    runner.runAsync(sessionId, message, new ResponseStreamCallback() {
                        @Override
                        public void onToken(String token) {
                            try {
                                JsonObject chunk = new JsonObject();
                                chunk.addProperty("token", token);
                                out.write(("data: " + JsonUtil.toJson(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            } catch (IOException e) {
                                ILog.d(TAG, "onToken write error: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onComplete(String fullText) {
                            try {
                                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                out.close();
                            } catch (IOException e) {
                                ILog.d(TAG, "onComplete write error: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onError(Exception e) {
                            try {
                                ILog.d(TAG, "runAsync error: " + e.getMessage());
                                out.close();
                            } catch (IOException ignored) {
                            }
                        }
                    });
                } else {
                    String reply = runner.run(sessionId, message);
                    JsonObject response = new JsonObject();
                    response.addProperty("reply", reply);
                    ctx.json(JsonUtil.toJson(response));
                }
            });
        });
    }
}
