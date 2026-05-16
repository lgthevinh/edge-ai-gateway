package thingai.edge.aigateway.handler.rag;

import org.thingai.base.dao.annotations.DaoColumn;
import org.thingai.base.dao.annotations.DaoTable;
import org.thingai.sdk.ai.vector.dao.DaoEmbedding;

@DaoTable(name = "rag_chunks", version = 1)
public class RagChunk {
    @DaoColumn(name = "chunk_id", primaryKey = true, nullable = false, unique = true)
    public String chunkId;

    @DaoColumn(name = "title", nullable = false)
    public String title;

    @DaoColumn(name = "content", nullable = false)
    public String content;

    @DaoColumn(name = "source")
    public String source;

    @DaoColumn(name = "embedding")
    @DaoEmbedding
    public float[] embedding;

    @DaoColumn(name = "created_at", nullable = false)
    public long createdAt;

    @DaoColumn(name = "updated_at", nullable = false)
    public long updatedAt;

    public RagChunk() {
    }

    public RagChunk(String chunkId, String title, String content, String source, long createdAt, long updatedAt) {
        this.chunkId = chunkId;
        this.title = title;
        this.content = content;
        this.source = source;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
