package thingai.edge.aigateway.agent.tools;

import com.google.gson.JsonObject;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.knowledgebase.KnowledgeDocument;
import thingai.edge.aigateway.knowledgebase.KnowledgeDocumentService;
import thingai.edge.aigateway.utils.JsonUtil;

public class ReadDocumentTool implements IAgentTool {
    private final KnowledgeDocumentService documentService;

    public ReadDocumentTool(KnowledgeDocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public String getName() {
        return "read_document";
    }

    @Override
    public String getDescription() {
        return "Knowledge base tool: read the full Markdown/text content of one user-uploaded knowledge document by document_id. Use after list_documents to answer questions grounded in local saved documents.";
    }

    @Override
    public String getParametersJson() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"document_id\":{\"type\":\"string\",\"description\":\"Document id returned by list_documents\"}"
                + "},\"required\":[\"document_id\"]}";
    }

    @Override
    public String execute(String paramsJson) {
        JsonObject params = JsonUtil.fromJson(paramsJson, JsonObject.class);
        String documentId = params != null && params.has("document_id") ? params.get("document_id").getAsString() : "";
        KnowledgeDocument document = documentService.getDocument(documentId);

        JsonObject result = new JsonObject();
        if (document == null) {
            result.addProperty("error", "document not found: " + documentId);
            return JsonUtil.toJson(result);
        }

        result.addProperty("document_id", document.documentId);
        result.addProperty("path", document.path);
        result.addProperty("title", document.title);
        result.addProperty("description", document.description);
        result.addProperty("content", document.content);
        result.addProperty("updated_at", document.updatedAt);
        return JsonUtil.toJson(result);
    }
}
