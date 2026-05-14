package thingai.edge.aigateway.api.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.apibuilder.EndpointGroup;
import thingai.edge.aigateway.EdgeAiGateway;
import thingai.edge.aigateway.handler.knowledge.DocumentImportResult;
import thingai.edge.aigateway.handler.knowledge.KnowledgeDocument;
import thingai.edge.aigateway.utils.JsonUtil;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;

public class RouteDocuments implements EndpointGroup {
    @Override
    public void addEndpoints() {
        path("documents", () -> {
            get(ctx -> {
                JsonArray documents = new JsonArray();
                for (KnowledgeDocument document : EdgeAiGateway.getKnowledgeHandler().listDocuments()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("title", document.title);
                    item.addProperty("description", document.description);
                    item.addProperty("updated_at", document.updatedAt);
                    documents.add(item);
                }

                JsonObject result = new JsonObject();
                result.add("documents", documents);
                ctx.json(JsonUtil.toJson(result));
            });

            post("/refresh", ctx -> {
                DocumentImportResult importResult = EdgeAiGateway.getKnowledgeHandler().importMarkdownDocuments();
                ctx.json(JsonUtil.toJson(importResult));
            });

            post("/upload", ctx -> {
                try {
                    JsonObject body = JsonUtil.fromJson(ctx.body(), JsonObject.class);
                    if (body == null || !body.has("title") || !body.has("description") || !body.has("content")) {
                        ctx.status(400).result("{\"error\":\"title, description, and content are required\"}");
                        return;
                    }

                    KnowledgeDocument document = EdgeAiGateway.getKnowledgeHandler().saveDocument(
                            body.get("title").getAsString(),
                            body.get("description").getAsString(),
                            body.get("content").getAsString()
                    );

                    JsonObject result = new JsonObject();
                    result.addProperty("title", document.title);
                    result.addProperty("description", document.description);
                    result.addProperty("updated_at", document.updatedAt);
                    ctx.json(JsonUtil.toJson(result));
                } catch (Exception e) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.getMessage());
                    ctx.status(500).json(JsonUtil.toJson(error));
                }
            });
        });
    }
}
