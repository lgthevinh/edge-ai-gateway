package thingai.edge.aigateway.api.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.apibuilder.EndpointGroup;
import thingai.edge.aigateway.EdgeAiService;
import thingai.edge.aigateway.handler.rag.RagChunk;
import thingai.edge.aigateway.handler.rag.RagSearchResult;
import thingai.edge.aigateway.utils.JsonUtil;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;

public class RouteRag implements EndpointGroup {
    @Override
    public void addEndpoints() {
        path("rag/chunks", () -> {
            get(ctx -> {
                JsonArray chunks = new JsonArray();
                for (RagChunk chunk : EdgeAiService.getRagHandler().listChunks()) {
                    chunks.add(toSummaryJson(chunk));
                }
                JsonObject result = new JsonObject();
                result.add("chunks", chunks);
                ctx.json(JsonUtil.toJson(result));
            });

            get("/{chunkId}", ctx -> {
                RagChunk chunk = EdgeAiService.getRagHandler().getChunk(ctx.pathParam("chunkId"));
                if (chunk == null) {
                    ctx.status(404).result("{\"error\":\"chunk not found\"}");
                    return;
                }
                ctx.json(JsonUtil.toJson(toDetailJson(chunk)));
            });

            delete("/{chunkId}", ctx -> {
                try {
                    boolean deleted = EdgeAiService.getRagHandler().deleteChunk(ctx.pathParam("chunkId"));
                    if (!deleted) {
                        ctx.status(404).result("{\"error\":\"chunk not found\"}");
                        return;
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("deleted", true);
                    result.addProperty("chunk_id", ctx.pathParam("chunkId"));
                    ctx.json(JsonUtil.toJson(result));
                } catch (Exception e) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.getMessage());
                    ctx.status(500).json(JsonUtil.toJson(error));
                }
            });

            post("/search", ctx -> {
                try {
                    JsonObject body = JsonUtil.fromJson(ctx.body(), JsonObject.class);
                    if (body == null || !body.has("query")) {
                        ctx.status(400).result("{\"error\":\"query is required\"}");
                        return;
                    }
                    String query = body.get("query").getAsString();
                    int topK = body.has("top_k") ? body.get("top_k").getAsInt() : 5;
                    JsonArray chunks = new JsonArray();
                    for (RagSearchResult searchResult : EdgeAiService.getRagHandler().searchChunks(query, topK)) {
                        chunks.add(toSearchJson(searchResult));
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("query", query);
                    result.add("chunks", chunks);
                    ctx.json(JsonUtil.toJson(result));
                } catch (Exception e) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.getMessage());
                    ctx.status(500).json(JsonUtil.toJson(error));
                }
            });

            post(ctx -> {
                try {
                    JsonObject body = JsonUtil.fromJson(ctx.body(), JsonObject.class);
                    if (body == null || !body.has("title") || !body.has("content")) {
                        ctx.status(400).result("{\"error\":\"title and content are required\"}");
                        return;
                    }
                    String chunkId = body.has("chunk_id") && !body.get("chunk_id").isJsonNull()
                            ? body.get("chunk_id").getAsString()
                            : null;
                    String source = body.has("source") && !body.get("source").isJsonNull()
                            ? body.get("source").getAsString()
                            : "";
                    RagChunk chunk = EdgeAiService.getRagHandler().saveChunk(
                            chunkId,
                            body.get("title").getAsString(),
                            body.get("content").getAsString(),
                            source
                    );
                    ctx.json(JsonUtil.toJson(toDetailJson(chunk)));
                } catch (IllegalArgumentException e) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.getMessage());
                    ctx.status(400).json(JsonUtil.toJson(error));
                } catch (Exception e) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.getMessage());
                    ctx.status(500).json(JsonUtil.toJson(error));
                }
            });
        });
    }

    private static JsonObject toSummaryJson(RagChunk chunk) {
        JsonObject item = new JsonObject();
        item.addProperty("chunk_id", chunk.chunkId);
        item.addProperty("title", chunk.title);
        item.addProperty("source", chunk.source);
        item.addProperty("updated_at", chunk.updatedAt);
        return item;
    }

    private static JsonObject toDetailJson(RagChunk chunk) {
        JsonObject item = toSummaryJson(chunk);
        item.addProperty("content", chunk.content);
        item.addProperty("created_at", chunk.createdAt);
        return item;
    }

    private static JsonObject toSearchJson(RagSearchResult result) {
        JsonObject item = toDetailJson(result.getChunk());
        item.addProperty("distance", result.getDistance());
        return item;
    }
}
