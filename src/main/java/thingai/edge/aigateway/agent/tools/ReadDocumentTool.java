package thingai.edge.aigateway.agent.tools;

import com.google.gson.JsonObject;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.handler.knowledge.KnowledgeDocument;
import thingai.edge.aigateway.handler.knowledge.KnowledgeHandler;
import thingai.edge.aigateway.utils.JsonUtil;

public class ReadDocumentTool implements IAgentTool {
    private final KnowledgeHandler knowledgeHandler;

    public ReadDocumentTool(KnowledgeHandler knowledgeHandler) {
        this.knowledgeHandler = knowledgeHandler;
    }

    @Override
    public String getName() {
        return "read_document";
    }

    @Override
    public String getDescription() {
        return "Knowledge base tool: read the full Markdown/text content of one user-uploaded knowledge document by title. Use after list_documents to answer questions grounded in local saved documents.";
    }

    @Override
    public String getParametersJson() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"title\":{\"type\":\"string\",\"description\":\"Document title returned by list_documents\"}"
                + "},\"required\":[\"title\"]}";
    }

    @Override
    public String execute(String paramsJson) {
        JsonObject params = JsonUtil.fromJson(paramsJson, JsonObject.class);
        String title = params != null && params.has("title") ? params.get("title").getAsString() : "";
        KnowledgeDocument document = knowledgeHandler.getDocument(title);

        JsonObject result = new JsonObject();
        if (document == null) {
            result.addProperty("error", "document not found: " + title);
            return JsonUtil.toJson(result);
        }

        result.addProperty("title", document.title);
        result.addProperty("description", document.description);
        result.addProperty("content", document.content);
        result.addProperty("updated_at", document.updatedAt);
        return JsonUtil.toJson(result);
    }
}
