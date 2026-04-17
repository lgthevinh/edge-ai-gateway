package thingai.edge.aigateway.api;

import io.javalin.Javalin;
import thingai.edge.aigateway.api.routes.RouteChat;
import thingai.edge.aigateway.api.routes.RouteRoot;

import static io.javalin.apibuilder.ApiBuilder.path;

/**
 * API server wrapping Javalin.
 */
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
        app = Javalin.create(config -> {
            config.routes.apiBuilder(() -> path("api", () -> {
                new RouteRoot().addEndpoints();
                new RouteChat().addEndpoints();
            }));
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
