package thingai.edge.aigateway.handler.knowledge;

import org.thingai.base.dao.annotations.DaoColumn;
import org.thingai.base.dao.annotations.DaoTable;

@DaoTable(name = "knowledge_documents", version = 4)
public class KnowledgeDocument {

    @DaoColumn(name = "title", primaryKey = true, nullable = false, unique = true)
    public String title;

    @DaoColumn(name = "description")
    public String description;

    @DaoColumn(name = "content")
    public String content;

    @DaoColumn(name = "enabled")
    public Boolean enabled;

    @DaoColumn(name = "created_at", nullable = false)
    public long createdAt;

    @DaoColumn(name = "updated_at", nullable = false)
    public long updatedAt;

    public KnowledgeDocument() {
    }

    public KnowledgeDocument(String title, String description, String content, Boolean enabled, long createdAt, long updatedAt) {
        this.title = title;
        this.description = description;
        this.content = content;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
