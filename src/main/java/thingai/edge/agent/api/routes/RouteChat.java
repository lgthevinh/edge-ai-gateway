package thingai.edge.agent.api.routes;

import io.javalin.apibuilder.EndpointGroup;

import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;

public class RouteChat implements EndpointGroup {
    @Override
    public void addEndpoints() {
        path("chat", () -> {
            post(ctx -> {
                // TODO: forward to inference
                ctx.json("{\"reply\":\"echo\"}");
            });
            get("/history", ctx -> {
                // TODO: return chat history
                ctx.json("[]");
            });
        });
    }
}
