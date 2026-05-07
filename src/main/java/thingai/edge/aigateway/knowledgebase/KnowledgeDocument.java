package thingai.edge.aigateway.knowledgebase;

import org.thingai.base.dao.annotations.DaoColumn;
import org.thingai.base.dao.annotations.DaoTable;

@DaoTable(name = "knowledge_documents", version = 1)
public class KnowledgeDocument {

    @DaoColumn(name = "document_id", primaryKey = true, nullable = false)
    public String documentId;

    @DaoColumn(name = "path", nullable = false)
    public String path;

    @DaoColumn(name = "title")
    public String title;

    @DaoColumn(name = "description")
    public String description;

    @DaoColumn(name = "content")
    public String content;

    @DaoColumn(name = "content_hash")
    public String contentHash;

    @DaoColumn(name = "created_at", nullable = false)
    public long createdAt;

    @DaoColumn(name = "updated_at", nullable = false)
    public long updatedAt;

    public KnowledgeDocument() {
    }

    public KnowledgeDocument(String documentId, String path, String title, String description,
                             String content, String contentHash, long createdAt, long updatedAt) {
        this.documentId = documentId;
        this.path = path;
        this.title = title;
        this.description = description;
        this.content = content;
        this.contentHash = contentHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
