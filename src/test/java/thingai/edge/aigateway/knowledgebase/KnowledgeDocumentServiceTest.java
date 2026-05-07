package thingai.edge.aigateway.knowledgebase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thingai.base.dao.Dao;
import org.thingai.base.dao.Migration;
import thingai.edge.aigateway.agent.tools.ListDocumentsTool;
import thingai.edge.aigateway.agent.tools.ReadDocumentTool;

import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeDocumentServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void importsMarkdownDocumentsAndUpsertsByRelativePath() throws Exception {
        Files.writeString(tempDir.resolve("guide.md"), """
                ---
                description: Local setup guide
                ---
                # Setup

                First paragraph.
                """);
        MemoryDao dao = new MemoryDao();
        KnowledgeDocumentService service = new KnowledgeDocumentService(dao, tempDir.toString());

        DocumentImportResult first = service.importMarkdownDocuments();
        assertEquals(1, first.scanned);
        assertEquals(1, first.inserted);
        assertEquals("Setup", service.getDocument("guide.md").title);
        assertEquals("Local setup guide", service.getDocument("guide.md").description);

        Files.writeString(tempDir.resolve("guide.md"), """
                # Setup Updated

                Updated paragraph.
                """);
        DocumentImportResult second = service.importMarkdownDocuments();
        assertEquals(1, second.updated);
        assertEquals(1, service.listDocuments().length);
        assertEquals("Setup Updated", service.getDocument("guide.md").title);
    }

    @Test
    void documentToolsListMetadataAndReadFullMarkdown() throws Exception {
        Files.writeString(tempDir.resolve("notes.md"), """
                # Notes

                Details for the assistant.
                """);
        KnowledgeDocumentService service = new KnowledgeDocumentService(new MemoryDao(), tempDir.toString());
        service.importMarkdownDocuments();

        String listResult = new ListDocumentsTool(service).execute("{}");
        assertTrue(listResult.contains("notes.md"));
        assertTrue(listResult.contains("Notes"));

        String readResult = new ReadDocumentTool(service).execute("{\"document_id\":\"notes.md\"}");
        assertTrue(readResult.contains("Details for the assistant."));
    }

    @Test
    void saveTextDocumentWritesFileAndImportsRecord() throws Exception {
        KnowledgeDocumentService service = new KnowledgeDocumentService(new MemoryDao(), tempDir.toString());

        KnowledgeDocument document = service.saveTextDocument("uploads/readme.txt", """
                Project notes

                Uploaded through the UI.
                """);

        assertEquals("uploads/readme.txt", document.documentId);
        assertEquals("readme", document.title);
        assertTrue(Files.exists(tempDir.resolve("uploads/readme.txt")));
        assertTrue(service.getDocument("uploads/readme.txt").content.contains("Uploaded through the UI."));
    }

    private static class MemoryDao implements Dao {
        private final Map<String, KnowledgeDocument> documents = new LinkedHashMap<>();

        @Override
        public void initDao(Class[] classes) {
        }

        @Override
        public void initDao(Class[] classes, Migration... migrations) {
        }

        @Override
        public <T> T[] readAll(Class<T> cls) {
            if (cls == KnowledgeDocument.class) return toArray(cls, documents.values());
            return emptyArray(cls);
        }

        @Override
        public <T> void insertOrUpdate(T entity) {
            if (entity instanceof KnowledgeDocument document) {
                documents.put(document.documentId, document);
            }
        }

        @Override
        public <T> void insertOrUpdate(Class<T> cls, T entity) {
            insertOrUpdate(entity);
        }

        @Override
        public <T> void insertBatch(T[] entities) {
            for (T entity : entities) insertOrUpdate(entity);
        }

        @Override
        public <T, K> void delete(Class<T> cls, K key) {
        }

        @Override
        public <T> void delete(T entity) {
        }

        @Override
        public <T> void deleteByColumn(Class<T> cls, String column, String value) {
        }

        @Override
        public <T> void deleteAll(Class<T> cls) {
        }

        @Override
        public <T> T[] query(Class<T> cls, String where, String value) {
            if (cls == KnowledgeDocument.class && "document_id = ?".equals(where)) {
                KnowledgeDocument document = documents.get(value);
                if (document == null) return emptyArray(cls);
                return toArray(cls, java.util.List.of(document));
            }
            return emptyArray(cls);
        }

        @Override
        public <T> T[] query(Class<T> cls, String[] columns, String[] values) {
            return emptyArray(cls);
        }

        @Override
        public <T> T[] query(Class<T> cls, String where) {
            return emptyArray(cls);
        }

        @Override
        public Map<String, Object>[] queryRaw(String sql) {
            return new Map[0];
        }

        private static <T> T[] emptyArray(Class<T> cls) {
            @SuppressWarnings("unchecked")
            T[] array = (T[]) Array.newInstance(cls, 0);
            return array;
        }

        private static <T> T[] toArray(Class<T> cls, Collection<?> values) {
            @SuppressWarnings("unchecked")
            T[] array = values.toArray((T[]) Array.newInstance(cls, values.size()));
            return array;
        }
    }
}
