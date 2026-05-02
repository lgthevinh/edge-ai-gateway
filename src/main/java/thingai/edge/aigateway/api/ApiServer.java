package thingai.edge.aigateway.api;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import thingai.edge.aigateway.agent.AgentOrchestrator;
import thingai.edge.aigateway.api.routes.RouteAgent;
import thingai.edge.aigateway.api.routes.RouteChat;
import thingai.edge.aigateway.api.routes.RouteRoot;

import static io.javalin.apibuilder.ApiBuilder.path;

public class ApiServer {

    private static final int DEFAULT_PORT = 8080;

    private Javalin app;
    private final int port;
    private final String llamaServerUrl;
    private final AgentOrchestrator orchestrator;

    public ApiServer(String llamaServerUrl, AgentOrchestrator orchestrator) {
        this(DEFAULT_PORT, llamaServerUrl, orchestrator);
    }

    public ApiServer(int port, String llamaServerUrl, AgentOrchestrator orchestrator) {
        this.port = port;
        this.llamaServerUrl = llamaServerUrl;
        this.orchestrator = orchestrator;
    }

    public void start() {
        RouteAgent routeAgent = new RouteAgent(orchestrator);
        app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
            config.routes.apiBuilder(() -> path("api", () -> {
                new RouteRoot().addEndpoints();
                new RouteChat(llamaServerUrl).addEndpoints();
                routeAgent.addEndpoints();
            }));
            config.routes.sse("/api/agent/chat/stream", routeAgent.sseHandler());
        }).start(port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    public Javalin getApp() {
        return app;
    }
}
