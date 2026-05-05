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
import java.util.*;

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

    private final Map<String, McpServerConnection> connections = new LinkedHashMap<>();
    private final Gson gson = new Gson();

    // ── Connection management ─────────────────────────────────────────────

    /**
     * Connects to a single MCP server by spawning its process.
     * If a server with the same name already exists, it is disconnected first.
     */
    public void connectStdio(String name, String command, List<String> args) {
        disconnect(name);
        try {
            McpServerConnection conn = McpServerConnection.connect(name, command, args);
            connections.put(name, conn);
        } catch (Exception e) {
            ILog.d(TAG, "Failed to connect MCP server '" + name + "': " + e.getMessage());
        }
    }

    public void connectHttp(String name, String url, String endpoint, Map<String, String> headers) {
        disconnect(name);
        try {
            McpServerConnection conn = McpServerConnection.connect(name, url, endpoint, headers);
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
                String name    = server.get("name").getAsString();

                // if command detected, use stdio transport; otherwise look for url+endpoint for http transport
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
                }

                if (server.has("url")) {
                    String url = server.get("url").getAsString();
                    String endpoint = server.get("endpoint").getAsString();

                    Map<String, String> headers = new HashMap<>();
                    if (server.has("headers")) {
                        JsonObject headersJson = server.getAsJsonObject("headers");
                        for (Map.Entry<String, JsonElement> header : headersJson.entrySet()) {
                            headers.put(header.getKey(), header.getValue().getAsString());
                        }
                    }

                    ILog.d(TAG, "loadConfig", "http", name);
                    connectHttp(name, url, endpoint, headers);
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
        return connections.values().stream()
                .flatMap(c -> c.getToolAdapters().stream())
                .toArray(IAgentTool[]::new);
    }

    /** Returns the number of currently connected servers. */
    public int serverCount() { return connections.size(); }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void close() {
        connections.values().forEach(McpServerConnection::close);
        connections.clear();
    }
}
