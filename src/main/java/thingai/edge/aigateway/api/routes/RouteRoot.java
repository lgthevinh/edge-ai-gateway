package thingai.edge.aigateway.api.routes;

import io.javalin.apibuilder.EndpointGroup;

import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.get;

public class RouteRoot implements EndpointGroup {
    @Override
    public void addEndpoints() {
        path("", () -> {
            get(ctx -> {
                ctx.result("Hello, World!");
            });
            get("/health", ctx -> {
                ctx.json("{\"status\":\"ok\"}");
            });
        });
    }
}
