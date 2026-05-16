package thingai.edge.aigateway.handler.knowledge;

import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

public class KnowledgeHandler {
    private static final String TAG = "KnowledgeHandler";
    private static final String SYSTEM_CONTEXT_HEADER = """
            ## Knowledge Base

            The following saved knowledge is already available as grounding context. Use it directly when relevant. If a question needs details that are not present here, use the RAG search tool.
            """.stripIndent();

    private final Dao dao;

    public KnowledgeHandler(Dao dao) {
        if (dao == null) {
            throw new IllegalArgumentException("dao is required");
        }
        this.dao = dao;
    }

    public KnowledgeDocument[] listDocuments() {
        KnowledgeDocument[] documents = dao.readAll(KnowledgeDocument.class);
        if (documents == null) return new KnowledgeDocument[0];
        Arrays.sort(documents, Comparator
                .comparingLong((KnowledgeDocument d) -> d.createdAt)
                .thenComparing(d -> d.title == null ? "" : d.title));
        return documents;
    }

    public KnowledgeDocument getDocument(String title) {
        if (title == null || title.isBlank()) return null;
        KnowledgeDocument[] documents = dao.query(KnowledgeDocument.class, "title", title);
        return documents != null && documents.length > 0 ? documents[0] : null;
    }

    public boolean deleteDocument(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        KnowledgeDocument existing = getDocument(title);
        if (existing == null) {
            return false;
        }
        dao.deleteByColumn(KnowledgeDocument.class, "title", title);
        return true;
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
        dao.insertOrUpdate(document);
        return document;
    }

    public String buildSystemInstructionContext(int maxChars) {
        if (maxChars <= 0) return "";
        KnowledgeDocument[] documents = listDocuments();
        if (documents.length == 0) return "";

        StringBuilder documentsContext = new StringBuilder();
        for (KnowledgeDocument document : documents) {
            if (document == null || isBlank(document.title) || isBlank(document.content)) {
                continue;
            }

            String block = "\n\n### " + document.title.trim()
                    + "\nDescription: " + safeTrim(document.description)
                    + "\nContent:\n" + document.content.trim();
            if (!appendWithLimit(documentsContext, block, maxChars)) {
                ILog.d(TAG, "buildSystemInstructionContext", "truncated at maxChars=" + maxChars);
                break;
            }
        }

        if (documentsContext.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        appendWithLimit(context, SYSTEM_CONTEXT_HEADER.trim(), maxChars);
        appendWithLimit(context, documentsContext.toString(), maxChars);
        return context.toString();
    }

    public DocumentImportResult importMarkdownDocuments() {
        return new DocumentImportResult();
    }

    private static boolean appendWithLimit(StringBuilder builder, String value, int maxChars) {
        if (value == null || value.isEmpty() || builder.length() >= maxChars) {
            return builder.length() < maxChars;
        }
        int remaining = maxChars - builder.length();
        if (value.length() <= remaining) {
            builder.append(value);
            return true;
        }
        if (remaining > 0) {
            builder.append(value, 0, remaining);
        }
        return false;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
