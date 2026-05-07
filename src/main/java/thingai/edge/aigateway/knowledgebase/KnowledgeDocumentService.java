package thingai.edge.aigateway.knowledgebase;

import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import org.thingai.platform.dao.DaoFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;

public class KnowledgeDocumentService {
    private static final String TAG = "KnowledgeDocumentService";

    private final Dao dao;
    private final DaoFile daoFile;
    private final Path rootPath;

    public KnowledgeDocumentService(Dao dao, String rootPath) {
        this.dao = dao;
        this.rootPath = Path.of(rootPath).normalize();
        this.daoFile = new DaoFile(this.rootPath.toString());
    }

    public String getRootPath() {
        return daoFile.getRootPath();
    }

    public DocumentImportResult importMarkdownDocuments() {
        DocumentImportResult result = new DocumentImportResult();
        try {
            Files.createDirectories(rootPath);
            try (var paths = Files.walk(rootPath)) {
                paths.filter(Files::isRegularFile)
                        .filter(this::isKnowledgeText)
                        .sorted()
                        .forEach(path -> importFile(path, result));
            }
        } catch (IOException e) {
            result.failed++;
            ILog.d(TAG, "importMarkdownDocuments failed: " + e.getMessage());
        }
        return result;
    }

    public KnowledgeDocument[] listDocuments() {
        KnowledgeDocument[] documents = dao.readAll(KnowledgeDocument.class);
        if (documents == null) return new KnowledgeDocument[0];
        Arrays.sort(documents, Comparator.comparing(d -> d.path == null ? "" : d.path));
        return documents;
    }

    public KnowledgeDocument getDocument(String documentId) {
        if (documentId == null || documentId.isBlank()) return null;
        KnowledgeDocument[] documents = dao.query(KnowledgeDocument.class, "document_id = ?", normalizeId(documentId));
        return documents != null && documents.length > 0 ? documents[0] : null;
    }

    public KnowledgeDocument saveTextDocument(String fileName, String content) throws IOException {
        Files.createDirectories(rootPath);
        String documentId = sanitizeDocumentPath(fileName);
        Path target = rootPath.resolve(documentId).normalize();
        if (!target.startsWith(rootPath)) {
            throw new IOException("invalid document path");
        }
        Files.createDirectories(target.getParent());
        Files.writeString(
                target,
                content != null ? content : "",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        DocumentImportResult result = new DocumentImportResult();
        try {
            return importFileInternal(target, result);
        } catch (Exception e) {
            throw new IOException("failed to import uploaded document: " + e.getMessage(), e);
        }
    }

    private void importFile(Path path, DocumentImportResult result) {
        try {
            importFileInternal(path, result);
        } catch (Exception e) {
            result.failed++;
            ILog.d(TAG, "importFile failed for " + path + ": " + e.getMessage());
        }
    }

    private KnowledgeDocument importFileInternal(Path path, DocumentImportResult result) throws Exception {
        result.scanned++;
        String documentId = rootPath.relativize(path).toString().replace('\\', '/');
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String hash = sha256(content);
        KnowledgeDocument existing = getDocument(documentId);

        if (existing != null && hash.equals(existing.contentHash)) {
            result.unchanged++;
            return existing;
        }

        long now = System.currentTimeMillis();
        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                documentId,
                extractTitle(documentId, content),
                extractDescription(content),
                content,
                hash,
                existing != null ? existing.createdAt : now,
                now
        );
        dao.insertOrUpdate(document);
        if (existing == null) result.inserted++;
        else result.updated++;
        return document;
    }

    private boolean isKnowledgeText(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt");
    }

    private String normalizeId(String documentId) {
        return documentId.replace('\\', '/').trim();
    }

    private String sanitizeDocumentPath(String fileName) throws IOException {
        String cleanName = fileName == null || fileName.isBlank() ? "upload-" + System.currentTimeMillis() + ".md" : fileName;
        cleanName = cleanName.replace('\\', '/').trim();
        while (cleanName.startsWith("/")) cleanName = cleanName.substring(1);
        Path normalized = Path.of(cleanName).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..") || normalized.toString().isBlank()) {
            throw new IOException("invalid document path");
        }
        String value = normalized.toString().replace('\\', '/');
        String lower = value.toLowerCase();
        if (!lower.endsWith(".md") && !lower.endsWith(".markdown") && !lower.endsWith(".txt")) {
            value = value + ".md";
        }
        return value;
    }

    private String extractTitle(String documentId, String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) return trimmed.substring(2).trim();
        }
        String fileName = Path.of(documentId).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String extractDescription(String content) {
        String frontmatter = extractFrontmatter(content);
        if (frontmatter != null) {
            for (String line : frontmatter.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("description:")) {
                    return stripQuotes(trimmed.substring("description:".length()).trim());
                }
            }
        }

        ArrayList<String> paragraph = new ArrayList<>();
        boolean insideFrontmatter = false;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if ("---".equals(trimmed)) {
                insideFrontmatter = !insideFrontmatter;
                continue;
            }
            if (insideFrontmatter || trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (!paragraph.isEmpty()) break;
                continue;
            }
            paragraph.add(trimmed);
        }
        return String.join(" ", paragraph);
    }

    private String extractFrontmatter(String content) {
        String[] lines = content.split("\\R", -1);
        if (lines.length == 0 || !"---".equals(lines[0].trim())) return null;
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) return builder.toString();
            builder.append(lines[i]).append('\n');
        }
        return null;
    }

    private String stripQuotes(String value) {
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String sha256(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
