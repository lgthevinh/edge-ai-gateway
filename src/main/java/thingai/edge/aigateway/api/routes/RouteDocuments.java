package thingai.edge.aigateway.api.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.apibuilder.EndpointGroup;
import thingai.edge.aigateway.EdgeAiGateway;
import thingai.edge.aigateway.handler.knowledge.DocumentImportResult;
import thingai.edge.aigateway.handler.knowledge.KnowledgeDocument;
import thingai.edge.aigateway.utils.JsonUtil;

import static io.javalin.apibuilder.ApiBuilder.delete;
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
                    documents.add(toSummaryJson(document));
                }

                JsonObject result = new JsonObject();
                result.add("documents", documents);
                ctx.json(JsonUtil.toJson(result));
            });

            get("/{title}", ctx -> {
                KnowledgeDocument document = EdgeAiGateway.getKnowledgeHandler().getDocument(ctx.pathParam("title"));
                if (document == null) {
                    ctx.status(404).result("{\"error\":\"document not found\"}");
                    return;
                }
                ctx.json(JsonUtil.toJson(toDetailJson(document)));
            });

            delete("/{title}", ctx -> {
                try {
                    boolean deleted = EdgeAiGateway.getKnowledgeHandler().deleteDocument(ctx.pathParam("title"));
                    if (!deleted) {
                        ctx.status(404).result("{\"error\":\"document not found\"}");
                        return;
                    }

                    JsonObject result = new JsonObject();
                    result.addProperty("deleted", true);
                    result.addProperty("title", ctx.pathParam("title"));
                    ctx.json(JsonUtil.toJson(result));
                } catch (Exception e) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.getMessage());
                    ctx.status(500).json(JsonUtil.toJson(error));
                }
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

                    ctx.json(JsonUtil.toJson(toDetailJson(document)));
                } catch (Exception e) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.getMessage());
                    ctx.status(500).json(JsonUtil.toJson(error));
                }
            });

            post(ctx -> {
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

                    ctx.json(JsonUtil.toJson(toDetailJson(document)));
                } catch (Exception e) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.getMessage());
                    ctx.status(500).json(JsonUtil.toJson(error));
                }
            });
        });
    }

    private static JsonObject toSummaryJson(KnowledgeDocument document) {
        JsonObject item = new JsonObject();
        item.addProperty("title", document.title);
        item.addProperty("description", document.description);
        item.addProperty("updated_at", document.updatedAt);
        return item;
    }

    private static JsonObject toDetailJson(KnowledgeDocument document) {
        JsonObject item = toSummaryJson(document);
        item.addProperty("content", document.content);
        item.addProperty("created_at", document.createdAt);
        return item;
    }

}
