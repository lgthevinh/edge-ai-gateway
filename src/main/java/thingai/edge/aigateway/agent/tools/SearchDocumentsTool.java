package thingai.edge.aigateway.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.handler.knowledge.KnowledgeDocument;
import thingai.edge.aigateway.handler.knowledge.KnowledgeHandler;
import thingai.edge.aigateway.utils.JsonUtil;

public class SearchDocumentsTool implements IAgentTool {
    private final KnowledgeHandler knowledgeHandler;

    public SearchDocumentsTool(KnowledgeHandler knowledgeHandler) {
        this.knowledgeHandler = knowledgeHandler;
    }

    @Override
    public String getName() {
        return "search_documents";
    }

    @Override
    public String getDescription() {
        return "Knowledge base tool: semantically search saved documents by meaning using a query. Use this first for topic, vague, or natural-language questions about local knowledge; then call read_document with a matching title when full content is needed.";
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

        JsonArray documents = new JsonArray();
        for (KnowledgeDocument document : knowledgeHandler.searchDocuments(query, topK)) {
            JsonObject item = new JsonObject();
            item.addProperty("title", document.title);
            item.addProperty("description", document.description);
            item.addProperty("updated_at", document.updatedAt);
            documents.add(item);
        }

        JsonObject result = new JsonObject();
        result.addProperty("query", query);
        result.add("documents", documents);
        return JsonUtil.toJson(result);
    }
}
