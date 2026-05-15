package thingai.edge.aigateway.agent.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.agent.IAgentTool;

import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages the set of MCP server connections and exposes their tools as a flat
 * {@link IAgentTool} array that can be passed to an {@link thingai.edge.aigateway.agent.Agent}.
 *
 * <p>Configuration is loaded from an optional {@code mcp-servers.json} file:
 * <pre>{@code
 * {
 *   "servers": [
 *     { "name": "filesystem", "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"] }
 *   ]
 * }
 * }</pre>
 *
 * <p>If the config file is absent or a server fails to connect, it is skipped with a
 * warning — the application continues with only the tools that did connect.
 */
public class McpRegistry implements Closeable {

    private static final String TAG = "McpRegistry";
    private static final String DEFAULT_HTTP_ENDPOINT = "/mcp";
    private static final String DEFAULT_SSE_ENDPOINT = "/sse";
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final Map<String, McpServerConnection> connections = new LinkedHashMap<>();
    private final Gson gson = new Gson();
    private final Map<String, String> env;

    public McpRegistry() {
        this(loadEnvironment());
    }

    public McpRegistry(Map<String, String> env) {
        this.env = new HashMap<>(env);
    }

    // ── Connection management ─────────────────────────────────────────────

    /**
     * Connects to a single MCP server by spawning its process.
     * If a server with the same name already exists, it is disconnected first.
     */
    public void connectStdio(String name, String command, List<String> args) {
        disconnect(name);
        try {
            McpServerConnection conn = McpServerConnection.connectStdio(name, command, args);
            connections.put(name, conn);
        } catch (Exception e) {
            ILog.d(TAG, "Failed to connect MCP server '" + name + "': " + e.getMessage());
        }
    }

    public void connectHttp(String name, String url, String endpoint, boolean useUrlAsEndpoint, Map<String, String> headers) {
        ILog.d(TAG, "connectHttp", name);
        disconnect(name);
        try {
            McpServerConnection conn = McpServerConnection.connectHttp(name, url, endpoint, useUrlAsEndpoint, headers);
            connections.put(name, conn);
        } catch (Exception e) {
            ILog.d(TAG, "Failed to connect MCP server '" + name + "': " + e.getMessage());
        }
    }

    public void connectSse(String name, String url, String endpoint, boolean useUrlAsEndpoint, Map<String, String> headers) {
        disconnect(name);
        try {
            McpServerConnection conn = McpServerConnection.connectSse(name, url, endpoint, useUrlAsEndpoint, headers);
            connections.put(name, conn);
        } catch (Exception e) {
            ILog.d(TAG, "Failed to connect MCP server '" + name + "': " + e.getMessage());
        }
    }

    public void disconnect(String name) {
        McpServerConnection conn = connections.remove(name);
        if (conn != null) conn.close();
    }

    // ── Config loading ────────────────────────────────────────────────────

    /**
     * Loads and connects all servers from a JSON config file.
     * No-op (with a log) if the file doesn't exist.
     *
     * @param configPath path to {@code mcp-servers.json}
     */
    public void loadConfig(String configPath) {
        File file = new File(configPath);
        if (!file.exists()) {
            ILog.d(TAG, "MCP config not found at '" + configPath + "' — skipping MCP tools");
            return;
        }
        try (FileReader reader = new FileReader(file)) {
            JsonObject root = gson.fromJson(reader, JsonObject.class);
            JsonArray servers = root.getAsJsonArray("servers");
            if (servers == null) {
                ILog.d(TAG, "mcp-servers.json has no 'servers' array");
                return;
            }
            for (JsonElement el : servers) {
                JsonObject server = el.getAsJsonObject();
                String name = getString(server, "name", null);
                if (isBlank(name)) {
                    ILog.d(TAG, "Skipping MCP server with missing 'name'");
                    continue;
                }
                String type = inferType(server);

                switch (type) {
                    case "http":
                        if (server.has("url")) {
                            String url = server.get("url").getAsString();
                            String endpoint = endpointOrDefault(server, DEFAULT_HTTP_ENDPOINT);
                            boolean useUrlAsEndpoint = getBoolean(server, "use_url_as_endpoint", false);
                            Map<String, String> headers = parseHeaders(server);

                            ILog.d(TAG, "loadConfig", "http", name);
                            connectHttp(name, url, endpoint, useUrlAsEndpoint, headers);
                        } else {
                            ILog.d(TAG, "Server '" + name + "' missing 'url' for http type");
                        }
                        break;
                    case "stdio":
                        if (server.has("command")) {
                            String command = server.get("command").getAsString();
                            List<String> args = new ArrayList<>();
                            if (server.has("args")) {
                                for (JsonElement arg : server.getAsJsonArray("args")) {
                                    args.add(arg.getAsString());
                                }
                            }
                            ILog.d(TAG, "loadConfig", "stdio", name);
                            connectStdio(name, command, args);
                        } else {
                            ILog.d(TAG, "Server '" + name + "' missing 'command' for stdio type");
                        }
                        break;
                    case "sse":
                        if (server.has("url")) {
                            String url = server.get("url").getAsString();
                            String endpoint = endpointOrDefault(server, DEFAULT_SSE_ENDPOINT);
                            boolean useUrlAsEndpoint = getBoolean(server, "use_url_as_endpoint", false);
                            Map<String, String> headers = parseHeaders(server);

                            ILog.d(TAG, "loadConfig", "sse", name);
                            connectSse(name, url, endpoint, useUrlAsEndpoint, headers);
                        } else {
                            ILog.d(TAG, "Server '" + name + "' missing 'url' for sse type");
                        }
                        break;
                    default:
                        ILog.d(TAG, "Server '" + name + "' has unsupported MCP type '" + type + "'");
                        break;
                }
            }
        } catch (IOException e) {
            ILog.d(TAG, "Error reading mcp-servers.json: " + e.getMessage());
        } catch (Exception e) {
            ILog.d(TAG, "Error parsing mcp-servers.json: " + e.getMessage());
        }
    }

    // ── Tool access ───────────────────────────────────────────────────────

    /**
     * Returns a flat array of all tools from all connected servers.
     * Returns an empty array if no servers are connected.
     */
    public IAgentTool[] getAllTools() {
        Map<String, IAgentTool> toolsByName = new LinkedHashMap<>();
        for (McpServerConnection connection : connections.values()) {
            for (McpToolAdapter tool : connection.getToolAdapters()) {
                IAgentTool existing = toolsByName.putIfAbsent(tool.getName(), tool);
                if (existing != null) {
                    ILog.d(TAG, "Skipping duplicate MCP tool '" + tool.getName()
                            + "' from server '" + connection.getName() + "'");
                }
            }
        }
        return toolsByName.values().toArray(IAgentTool[]::new);
    }

    /** Returns the number of currently connected servers. */
    public int serverCount() { return connections.size(); }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void close() {
        connections.values().forEach(McpServerConnection::close);
        connections.clear();
    }

    private static String inferType(JsonObject server) {
        String type = getString(server, "type", null);
        if (!isBlank(type)) return type.trim().toLowerCase(Locale.ROOT);
        if (server.has("command")) return "stdio";
        if (server.has("url")) return "http";
        return "unknown";
    }

    private static String endpointOrDefault(JsonObject server, String defaultEndpoint) {
        String endpoint = getString(server, "endpoint", defaultEndpoint);
        return isBlank(endpoint) ? defaultEndpoint : endpoint.trim();
    }

    private Map<String, String> parseHeaders(JsonObject server) {
        Map<String, String> headers = new LinkedHashMap<>();
        JsonObject headersJson = null;
        if (server.has("headers") && server.get("headers").isJsonObject()) {
            headersJson = server.getAsJsonObject("headers");
        } else if (server.has("header") && server.get("header").isJsonObject()) {
            headersJson = server.getAsJsonObject("header");
        }
        if (headersJson == null) return headers;

        for (Map.Entry<String, JsonElement> header : headersJson.entrySet()) {
            String value = header.getValue().isJsonNull() ? "" : header.getValue().getAsString();
            headers.put(header.getKey(), resolveEnvPlaceholders(value));
        }
        return headers;
    }

    private String resolveEnvPlaceholders(String value) {
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String replacement = env.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static String getString(JsonObject object, String key, String defaultValue) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return defaultValue;
        return element.getAsString();
    }

    private static boolean getBoolean(JsonObject object, String key, boolean defaultValue) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return defaultValue;
        return element.getAsBoolean();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static Map<String, String> loadEnvironment() {
        Map<String, String> values = new HashMap<>();
        values.putAll(loadEnvFile(".env"));
        values.putAll(System.getenv());
        return values;
    }

    private static Map<String, String> loadEnvFile(String path) {
        Map<String, String> values = new HashMap<>();
        Path envPath = Path.of(path);
        if (!Files.exists(envPath)) return values;

        try {
            for (String line : Files.readAllLines(envPath)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            ILog.d(TAG, "Error reading .env for MCP placeholders: " + e.getMessage());
        }
        return values;
    }
}
