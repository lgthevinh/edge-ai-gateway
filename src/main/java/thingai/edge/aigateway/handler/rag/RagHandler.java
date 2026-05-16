package thingai.edge.aigateway.handler.rag;

import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import org.thingai.sdk.ai.vector.dao.DaoVectorSqlite;
import org.thingai.sdk.ai.vector.define.VectorSearchResult;
import thingai.edge.aigateway.handler.embedding.EmbeddingHandler;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

public class RagHandler {
    private static final String TAG = "RagHandler";
    private static final int MAX_CHUNK_WORDS = 200;

    private final Dao dao;
    private final EmbeddingHandler embeddingHandler;

    public RagHandler(Dao dao, EmbeddingHandler embeddingHandler) {
        if (dao == null) {
            throw new IllegalArgumentException("dao is required");
        }
        this.dao = dao;
        this.embeddingHandler = embeddingHandler;
    }

    public RagSearchResult[] searchChunks(String query, int topK) {
        if (query == null || query.isBlank()) return new RagSearchResult[0];
        if (embeddingHandler == null) {
            ILog.d(TAG, "searchChunks skipped: embedding handler is not configured");
            return new RagSearchResult[0];
        }
        if (!(dao instanceof DaoVectorSqlite vectorDao)) {
            ILog.d(TAG, "searchChunks skipped: dao does not support vector search");
            return new RagSearchResult[0];
        }

        try {
            float[] queryEmbedding = embeddingHandler.embed(query);
            if (queryEmbedding == null || queryEmbedding.length == 0) {
                return new RagSearchResult[0];
            }
            VectorSearchResult<RagChunk>[] results = vectorDao.searchVectors(
                    RagChunk.class,
                    "embedding",
                    queryEmbedding,
                    Math.max(2, topK)
            );
            RagSearchResult[] chunks = new RagSearchResult[results.length];
            for (int i = 0; i < results.length; i++) {
                RagChunk chunk = results[i].getEntity();
                chunks[i] = new RagSearchResult(chunk, results[i].getDistance());
                ILog.d(TAG, "searchChunks", String.valueOf(results[i].getDistance()), chunk.title);
            }
            return chunks;
        } catch (UnsupportedOperationException e) {
            ILog.d(TAG, "searchChunks unsupported by vector DAO: " + e.getMessage());
            return new RagSearchResult[0];
        } catch (Exception e) {
            ILog.d(TAG, "searchChunks failed: " + e.getMessage());
            return new RagSearchResult[0];
        }
    }

    public RagChunk[] listChunks() {
        RagChunk[] chunks = dao.readAll(RagChunk.class);
        if (chunks == null) return new RagChunk[0];
        Arrays.sort(chunks, Comparator.comparing(c -> c.title == null ? "" : c.title));
        return chunks;
    }

    public RagChunk getChunk(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) return null;
        RagChunk[] chunks = dao.query(RagChunk.class, "chunk_id", chunkId);
        return chunks != null && chunks.length > 0 ? chunks[0] : null;
    }

    public boolean deleteChunk(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunk_id is required");
        }
        RagChunk existing = getChunk(chunkId);
        if (existing == null) return false;
        dao.deleteByColumn(RagChunk.class, "chunk_id", chunkId);
        return true;
    }

    public RagChunk saveChunk(String chunkId, String title, String content, String source) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        int wordCount = countWords(content);
        if (wordCount > MAX_CHUNK_WORDS) {
            throw new IllegalArgumentException("content chunk must be " + MAX_CHUNK_WORDS + " words or fewer; got " + wordCount);
        }

        String id = chunkId == null || chunkId.isBlank() ? UUID.randomUUID().toString() : chunkId;
        RagChunk existing = getChunk(id);
        String normalizedContent = content.trim();
        String normalizedTitle = title.trim();
        String normalizedSource = source != null ? source.trim() : "";
        if (existing != null
                && Objects.equals(existing.title, normalizedTitle)
                && Objects.equals(existing.content, normalizedContent)
                && Objects.equals(existing.source, normalizedSource)) {
            return existing;
        }

        long now = System.currentTimeMillis();
        RagChunk chunk = new RagChunk(
                id,
                normalizedTitle,
                normalizedContent,
                normalizedSource,
                existing != null ? existing.createdAt : now,
                now
        );
        if (existing != null && Objects.equals(existing.content, normalizedContent)) {
            chunk.embedding = existing.embedding;
        }
        embedChunk(chunk, existing);
        dao.insertOrUpdate(chunk);
        return chunk;
    }

    private void embedChunk(RagChunk chunk, RagChunk existing) {
        if (embeddingHandler == null) return;
        try {
            boolean contentChanged = existing == null || !Objects.equals(chunk.content, existing.content);
            if (!contentChanged && chunk.embedding != null && chunk.embedding.length > 0) {
                return;
            }
            chunk.embedding = embeddingHandler.embed(buildChunkEmbeddingInput(chunk));
        } catch (Exception e) {
            ILog.d(TAG, "embedChunk failed for " + chunk.title + ": " + e.getMessage());
        }
    }

    private String buildChunkEmbeddingInput(RagChunk chunk) {
        return "Title: " + chunk.title + "\nSource: " + safe(chunk.source) + "\nContent: " + chunk.content;
    }

    private static int countWords(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) return 0;
        return trimmed.split("\\s+").length;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
