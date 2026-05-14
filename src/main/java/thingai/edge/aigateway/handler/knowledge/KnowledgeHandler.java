package thingai.edge.aigateway.handler.knowledge;

import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import org.thingai.sdk.ai.vector.dao.DaoVectorSqlite;
import org.thingai.sdk.ai.vector.define.VectorSearchResult;
import thingai.edge.aigateway.handler.embedding.EmbeddingHandler;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

public class KnowledgeHandler {
    private static final String TAG = "KnowledgeHandler";

    private final Dao dao;
    private final EmbeddingHandler embeddingHandler;

    public KnowledgeHandler(Dao dao) {
        this(dao, null);
    }

    public KnowledgeHandler(Dao dao, EmbeddingHandler embeddingHandler) {
        if (dao == null) {
            throw new IllegalArgumentException("dao is required");
        }
        this.dao = dao;
        this.embeddingHandler = embeddingHandler;
    }

    public KnowledgeDocument[] listDocuments() {
        KnowledgeDocument[] documents = dao.readAll(KnowledgeDocument.class);
        if (documents == null) return new KnowledgeDocument[0];
        Arrays.sort(documents, Comparator.comparing(d -> d.title == null ? "" : d.title));
        return documents;
    }

    public KnowledgeDocument getDocument(String title) {
        if (title == null || title.isBlank()) return null;
        KnowledgeDocument[] documents = dao.query(KnowledgeDocument.class, "title = ?", title);
        return documents != null && documents.length > 0 ? documents[0] : null;
    }

    public KnowledgeDocument saveDocument(String title, String description, String content) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }

        KnowledgeDocument existing = getDocument(title);
        String documentContent = content != null ? content : "";
        if (existing != null
                && Objects.equals(description, existing.description)
                && Objects.equals(documentContent, existing.content)) {
            return existing;
        }

        long now = System.currentTimeMillis();
        KnowledgeDocument document = new KnowledgeDocument(
                title,
                description,
                documentContent,
                existing != null ? existing.createdAt : now,
                now
        );
        if (existing != null && Objects.equals(description, existing.description)) {
            document.embedding = existing.embedding;
        }
        embedDocument(document, existing);
        dao.insertOrUpdate(document);
        return document;
    }

    public KnowledgeDocument[] semanticSearch(String query, int topK) {
        if (query == null || query.isBlank()) return new KnowledgeDocument[0];
        if (embeddingHandler == null) {
            ILog.d(TAG, "semanticSearch skipped: embedding handler is not configured");
            return new KnowledgeDocument[0];
        }
        if (!(dao instanceof DaoVectorSqlite vectorDao)) {
            ILog.d(TAG, "semanticSearch skipped: dao does not support vector search");
            return new KnowledgeDocument[0];
        }

        try {
            float[] queryEmbedding = embeddingHandler.embed(query);
            if (queryEmbedding == null || queryEmbedding.length == 0) {
                return new KnowledgeDocument[0];
            }
            VectorSearchResult<KnowledgeDocument>[] results = vectorDao.searchVectors(
                    KnowledgeDocument.class,
                    "embedding",
                    queryEmbedding,
                    Math.max(1, topK)
            );
            if (results.length == 0) {
                return new KnowledgeDocument[0];
            }
            KnowledgeDocument[] documents = new KnowledgeDocument[results.length];
            for  (int i = 0; i < results.length; i++) {
                KnowledgeDocument document = results[i].getEntity();
                documents[i] = document;
            }
            return documents;
        } catch (UnsupportedOperationException e) {
            ILog.d(TAG, "semanticSearch unsupported by vector DAO: " + e.getMessage());
            return new KnowledgeDocument[0];
        } catch (Exception e) {
            e.printStackTrace();
            ILog.d(TAG, "semanticSearch failed: " + e.getMessage());
            return new KnowledgeDocument[0];
        }
    }

    public KnowledgeDocument[] searchDocuments(String query, int topK) {
        return semanticSearch(query, topK);
    }

    public DocumentImportResult importMarkdownDocuments() {
        return new DocumentImportResult();
    }

    private void embedDocument(KnowledgeDocument document, KnowledgeDocument existing) {
        if (embeddingHandler == null) return;
        try {
            boolean metadataChanged = existing == null
                    || !Objects.equals(document.title, existing.title)
                    || !Objects.equals(document.description, existing.description);
            if (!metadataChanged && document.embedding != null && document.embedding.length > 0) {
                return;
            }
            document.embedding = embeddingHandler.embed(buildDocumentEmbeddingInput(document));
        } catch (Exception e) {
            ILog.d(TAG, "embedDocument failed for " + document.title + ": " + e.getMessage());
        }
    }

    private String buildDocumentEmbeddingInput(KnowledgeDocument document) {
        String title = document.title == null ? "" : document.title.trim();
        String description = document.description == null ? "" : document.description.trim();
        return "Title: " + title + "\nDescription: " + description;
    }
}
