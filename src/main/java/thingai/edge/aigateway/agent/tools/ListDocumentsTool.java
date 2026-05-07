package thingai.edge.aigateway.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.knowledgebase.KnowledgeDocument;
import thingai.edge.aigateway.knowledgebase.KnowledgeDocumentService;
import thingai.edge.aigateway.utils.JsonUtil;

public class ListDocumentsTool implements IAgentTool {
    private final KnowledgeDocumentService documentService;

    public ListDocumentsTool(KnowledgeDocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public String getName() {
        return "list_documents";
    }

    @Override
    public String getDescription() {
        return "Knowledge base tool: list user-uploaded Markdown/text documents with document_id, title, description, and path. Use this first when the user asks about saved knowledge, uploaded documents, notes, manuals, specs, or available local knowledge.";
    }

    @Override
    public String getParametersJson() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public String execute(String paramsJson) {
        JsonArray documents = new JsonArray();
        for (KnowledgeDocument document : documentService.listDocuments()) {
            JsonObject item = new JsonObject();
            item.addProperty("document_id", document.documentId);
            item.addProperty("path", document.path);
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
