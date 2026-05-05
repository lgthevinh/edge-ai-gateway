package thingai.edge.aigateway.api;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import thingai.edge.aigateway.api.routes.RouteAgent;
import thingai.edge.aigateway.api.routes.RouteChat;
import thingai.edge.aigateway.api.routes.RouteRoot;

import static io.javalin.apibuilder.ApiBuilder.path;

public class ApiServer {

    private static final int DEFAULT_PORT = 8080;

    private Javalin app;
    private final int port;

    public ApiServer() {
        this(DEFAULT_PORT);
    }

    public ApiServer(int port) {
        this.port = port;
    }

    public void start() {
        RouteAgent routeAgent = new RouteAgent();
        app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
            config.routes.apiBuilder(() -> path("api", () -> {
                new RouteRoot().addEndpoints();
                new RouteChat().addEndpoints();
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
