package thingai.edge.aigateway.agent.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class McpToolAdapterTest {

    @Test
    void normalizeArgumentsDefaultsListDirectoryPathToConfiguredRoot() {
        Map<String, Object> args = McpToolAdapter.normalizeArguments(
                "list_directory",
                "{}",
                "/data/projects/edge-ai-gateway/"
        );

        assertEquals("/data/projects/edge-ai-gateway/", args.get("path"));
    }

    @Test
    void normalizeArgumentsDefaultsDirectoryTreePathToConfiguredRoot() {
        Map<String, Object> args = McpToolAdapter.normalizeArguments(
                "directory_tree",
                null,
                "/data/projects/edge-ai-gateway/"
        );

        assertEquals("/data/projects/edge-ai-gateway/", args.get("path"));
    }

    @Test
    void normalizeArgumentsDefaultsListDirectoryWithSizesPathToConfiguredRoot() {
        Map<String, Object> args = McpToolAdapter.normalizeArguments(
                "list_directory_with_sizes",
                "{}",
                "/data/projects/edge-ai-gateway/"
        );

        assertEquals("/data/projects/edge-ai-gateway/", args.get("path"));
    }

    @Test
    void normalizeArgumentsPreservesExplicitPath() {
        Map<String, Object> args = McpToolAdapter.normalizeArguments(
                "list_directory",
                "{\"path\":\"/tmp\"}",
                "/data/projects/edge-ai-gateway/"
        );

        assertEquals("/tmp", args.get("path"));
    }

    @Test
    void validateRequiredArgumentsRejectsMissingFilePath() {
        String error = McpToolAdapter.validateRequiredArguments(
                "read_text_file",
                Map.of(),
                "/data/projects/edge-ai-gateway/"
        );

        assertEquals("Missing required argument 'path'. Retry with a concrete file or directory path.", error);
    }

    @Test
    void validateRequiredArgumentsRejectsMissingWriteFilePath() {
        String error = McpToolAdapter.validateRequiredArguments(
                "write_file",
                Map.of(),
                "/data/projects/edge-ai-gateway/"
        );

        assertEquals("Missing required argument 'path'. Retry with a concrete file or directory path.", error);
    }

    @Test
    void validateRequiredArgumentsAcceptsDefaultedDirectoryPath() {
        Map<String, Object> args = McpToolAdapter.normalizeArguments(
                "list_directory",
                "{}",
                "/data/projects/edge-ai-gateway/"
        );

        assertNull(McpToolAdapter.validateRequiredArguments("list_directory", args, "/data/projects/edge-ai-gateway/"));
    }
}
