package thingai.edge.aigateway.api;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.thingai.base.dao.Dao;
import thingai.edge.aigateway.agent.IAgent;
import thingai.edge.aigateway.api.routes.RouteAgent;
import thingai.edge.aigateway.api.routes.RouteChat;
import thingai.edge.aigateway.api.routes.RouteRoot;

import static io.javalin.apibuilder.ApiBuilder.path;

public class ApiServer {

    private static final int DEFAULT_PORT = 8080;

    private Javalin app;
    private final int port;
    private final String llamaServerUrl;
    private final IAgent agent;
    private final Dao dao;

    public ApiServer(String llamaServerUrl, IAgent agent, Dao dao) {
        this(DEFAULT_PORT, llamaServerUrl, agent, dao);
    }

    public ApiServer(int port, String llamaServerUrl, IAgent agent, Dao dao) {
        this.port = port;
        this.llamaServerUrl = llamaServerUrl;
        this.agent = agent;
        this.dao = dao;
    }

    public void start() {
        app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
            config.routes.apiBuilder(() -> path("api", () -> {
                new RouteRoot().addEndpoints();
                new RouteChat(llamaServerUrl).addEndpoints();
                new RouteAgent(agent, dao).addEndpoints();
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
