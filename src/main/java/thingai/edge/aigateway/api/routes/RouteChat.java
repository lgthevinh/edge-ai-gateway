package thingai.edge.aigateway.api.routes;

import io.javalin.apibuilder.EndpointGroup;

import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;

public class RouteChat implements EndpointGroup {
    @Override
    public void addEndpoints() {
        path("chat", () -> {
            post(ctx -> {
                ctx.json("{\"reply\":\"echo\"}");
            });
            get("/history", ctx -> {
                ctx.json("[]");
            });
        });
    }
}
