package thingai.edge.aigateway.agent.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void loadConfigDefaultsMissingTypeWithCommandToStdio() throws IOException {
        Path config = writeConfig("""
                {
                  "servers": [
                    {
                      "name": "filesystem",
                      "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
                    }
                  ]
                }
                """);
        RecordingRegistry registry = new RecordingRegistry(Map.of());

        registry.loadConfig(config.toString());

        assertEquals(1, registry.calls.size());
        RecordedCall call = registry.calls.get(0);
        assertEquals("stdio", call.type);
        assertEquals("filesystem", call.name);
        assertEquals("npx", call.command);
        assertEquals(List.of("-y", "@modelcontextprotocol/server-filesystem", "/data"), call.args);
    }

    @Test
    void loadConfigDefaultsBlankHttpAndSseEndpoints() throws IOException {
        Path config = writeConfig("""
                {
                  "servers": [
                    {
                      "name": "streamable",
                      "type": "http",
                      "url": "https://example.test",
                      "endpoint": ""
                    },
                    {
                      "name": "legacy-sse",
                      "type": "sse",
                      "url": "https://sse.example.test",
                      "endpoint": ""
                    }
                  ]
                }
                """);
        RecordingRegistry registry = new RecordingRegistry(Map.of());

        registry.loadConfig(config.toString());

        assertEquals(2, registry.calls.size());
        assertEquals("http", registry.calls.get(0).type);
        assertEquals("/mcp", registry.calls.get(0).endpoint);
        assertEquals("sse", registry.calls.get(1).type);
        assertEquals("/sse", registry.calls.get(1).endpoint);
    }

    @Test
    void loadConfigPreservesRootSseEndpoint() throws IOException {
        Path config = writeConfig("""
                {
                  "servers": [
                    {
                      "name": "root-sse",
                      "type": "sse",
                      "url": "https://sse.example.test",
                      "endpoint": "/"
                    }
                  ]
                }
                """);
        RecordingRegistry registry = new RecordingRegistry(Map.of());

        registry.loadConfig(config.toString());

        assertEquals(1, registry.calls.size());
        assertEquals("sse", registry.calls.get(0).type);
        assertEquals("/", registry.calls.get(0).endpoint);
    }

    @Test
    void loadConfigResolvesHeaderEnvironmentPlaceholders() throws IOException {
        Path config = writeConfig("""
                {
                  "servers": [
                    {
                      "name": "rogo",
                      "type": "sse",
                      "url": "https://rogo.example.test",
                      "headers": {
                        "Authorization": "Bearer ${ROGO_MCP_TOKEN}"
                      }
                    }
                  ]
                }
                """);
        RecordingRegistry registry = new RecordingRegistry(Map.of("ROGO_MCP_TOKEN", "test-token"));

        registry.loadConfig(config.toString());

        assertEquals(1, registry.calls.size());
        assertEquals("/sse", registry.calls.get(0).endpoint);
        assertEquals("Bearer test-token", registry.calls.get(0).headers.get("Authorization"));
    }

    @Test
    void loadConfigSkipsUnknownType() throws IOException {
        Path config = writeConfig("""
                {
                  "servers": [
                    {
                      "name": "bad",
                      "type": "websocket",
                      "url": "https://example.test"
                    }
                  ]
                }
                """);
        RecordingRegistry registry = new RecordingRegistry(Map.of());

        registry.loadConfig(config.toString());

        assertEquals(0, registry.calls.size());
    }

    private Path writeConfig(String json) throws IOException {
        Path config = tempDir.resolve("mcp-servers.json");
        Files.writeString(config, json);
        return config;
    }

    private static class RecordingRegistry extends McpRegistry {
        private final List<RecordedCall> calls = new ArrayList<>();

        RecordingRegistry(Map<String, String> env) {
            super(env);
        }

        @Override
        public void connectStdio(String name, String command, List<String> args) {
            calls.add(RecordedCall.stdio(name, command, args));
        }

        @Override
        public void connectHttp(String name, String url, String endpoint, Map<String, String> headers) {
            calls.add(RecordedCall.remote("http", name, url, endpoint, headers));
        }

        @Override
        public void connectSse(String name, String url, String endpoint, Map<String, String> headers) {
            calls.add(RecordedCall.remote("sse", name, url, endpoint, headers));
        }
    }

    private static class RecordedCall {
        private final String type;
        private final String name;
        private final String command;
        private final List<String> args;
        private final String url;
        private final String endpoint;
        private final Map<String, String> headers;

        private RecordedCall(String type, String name, String command, List<String> args,
                             String url, String endpoint, Map<String, String> headers) {
            this.type = type;
            this.name = name;
            this.command = command;
            this.args = args;
            this.url = url;
            this.endpoint = endpoint;
            this.headers = headers;
        }

        private static RecordedCall stdio(String name, String command, List<String> args) {
            return new RecordedCall("stdio", name, command, List.copyOf(args), null, null, Map.of());
        }

        private static RecordedCall remote(String type, String name, String url, String endpoint,
                                           Map<String, String> headers) {
            return new RecordedCall(type, name, null, List.of(), url, endpoint, Map.copyOf(headers));
        }
    }
}
