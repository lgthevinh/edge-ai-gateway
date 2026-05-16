package thingai.edge.aigateway.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.handler.rag.RagChunk;
import thingai.edge.aigateway.handler.rag.RagHandler;
import thingai.edge.aigateway.handler.rag.RagSearchResult;
import thingai.edge.aigateway.utils.JsonUtil;

public class SearchDocumentsTool implements IAgentTool {
    private final RagHandler ragHandler;

    public SearchDocumentsTool(RagHandler ragHandler) {
        this.ragHandler = ragHandler;
    }

    @Override
    public String getName() {
        return "search_documents";
    }

    @Override
    public String getDescription() {
        return "RAG tool: semantically search short indexed content chunks by meaning. Use when the injected Knowledge Base context is insufficient or when the user asks for details that may be in indexed content.";
    }

    @Override
    public String getParametersJson() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"description\":\"Natural-language search query\"},"
                + "\"top_k\":{\"type\":\"integer\",\"description\":\"Maximum number of documents to return. Defaults to 5\"}"
                + "},\"required\":[\"query\"]}";
    }

    @Override
    public String execute(String paramsJson) {
        JsonObject params = JsonUtil.fromJson(paramsJson, JsonObject.class);
        String query = params != null && params.has("query") ? params.get("query").getAsString() : "";
        int topK = params != null && params.has("top_k") ? params.get("top_k").getAsInt() : 5;

        JsonArray chunks = new JsonArray();
        for (RagSearchResult searchResult : ragHandler.searchChunks(query, topK)) {
            RagChunk chunk = searchResult.getChunk();
            JsonObject item = new JsonObject();
            item.addProperty("type", "chunk");
            item.addProperty("chunk_id", chunk.chunkId);
            item.addProperty("title", chunk.title);
            item.addProperty("source", chunk.source);
            item.addProperty("content", chunk.content);
            item.addProperty("updated_at", chunk.updatedAt);
            item.addProperty("distance", searchResult.getDistance());
            chunks.add(item);
        }

        JsonObject result = new JsonObject();
        result.addProperty("query", query);
        result.add("chunks", chunks);
        return JsonUtil.toJson(result);
    }
}
