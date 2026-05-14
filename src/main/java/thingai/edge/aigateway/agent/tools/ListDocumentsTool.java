package thingai.edge.aigateway.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.handler.knowledge.KnowledgeDocument;
import thingai.edge.aigateway.handler.knowledge.KnowledgeHandler;
import thingai.edge.aigateway.utils.JsonUtil;

public class ListDocumentsTool implements IAgentTool {
    private final KnowledgeHandler knowledgeHandler;

    public ListDocumentsTool(KnowledgeHandler knowledgeHandler) {
        this.knowledgeHandler = knowledgeHandler;
    }

    @Override
    public String getName() {
        return "list_documents";
    }

    @Override
    public String getDescription() {
        return "Knowledge base tool: list user-uploaded Markdown/text documents with title and description. Use this first when the user asks about saved knowledge, uploaded documents, notes, manuals, specs, or available local knowledge.";
    }

    @Override
    public String getParametersJson() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public String execute(String paramsJson) {
        JsonArray documents = new JsonArray();
        for (KnowledgeDocument document : knowledgeHandler.listDocuments()) {
            JsonObject item = new JsonObject();
            item.addProperty("title", document.title);
            item.addProperty("description", document.description);
            item.addProperty("updated_at", document.updatedAt);
            documents.add(item);
        }

        JsonObject result = new JsonObject();
        result.add("documents", documents);
        return JsonUtil.toJson(result);
    }
}
