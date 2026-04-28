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
    private final String llamaServerUrl;

    public ApiServer(String llamaServerUrl) {
        this(DEFAULT_PORT, llamaServerUrl);
    }

    public ApiServer(int port, String llamaServerUrl) {
        this.port = port;
        this.llamaServerUrl = llamaServerUrl;
    }

    public void start() {
        app = Javalin.create(config -> {
            config.routes.apiBuilder(() -> path("api", () -> {
                new RouteRoot().addEndpoints();
                new RouteChat(llamaServerUrl).addEndpoints();
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
