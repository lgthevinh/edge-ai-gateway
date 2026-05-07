package thingai.edge.aigateway.agent.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.thingai.base.log.ILog;

import java.io.Closeable;
import java.util.List;
import java.util.Map;

/**
 * Manages the lifecycle of a single MCP server process connected via stdio.
 * Spawns the process, initializes the protocol handshake, fetches the tool list,
 * and exposes each tool as a {@link McpToolAdapter}.
 */
public class McpServerConnection implements Closeable {

    private static final String TAG = "McpServerConnection";

    private final String name;
    private final McpSyncClient client;
    private final List<McpToolAdapter> toolAdapters;

    private McpServerConnection(String name, McpSyncClient client, List<McpToolAdapter> toolAdapters) {
        this.name         = name;
        this.client       = client;
        this.toolAdapters = toolAdapters;
    }

    /**
     * Spawns the MCP server process, performs the protocol handshake, and
     * returns a fully initialised {@code McpServerConnection}.
     *
     * @param name    logical name for this server (used in logs)
     * @param command the executable to run (e.g. "npx", "python3")
     * @param args    arguments to pass to the executable
     */
    public static McpServerConnection connectStdio(String name, String command, List<String> args) {
        ServerParameters params = ServerParameters.builder(command)
                .args(args)
                .build();

        // StdioClientTransport takes ServerParameters and our Gson-backed mapper
        StdioClientTransport transport = new StdioClientTransport(params, new McpGsonJsonMapper());

        return buildMcpClient(name, transport);
    }

    public static McpServerConnection connectHttp(String name, String url, String endpoint, Map<String, String> headers) {
        McpClientTransport transport = HttpClientStreamableHttpTransport
                .builder(url)
                .httpRequestCustomizer((builder, method, endpoint1, body, context) -> {
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        builder.header(header.getKey(), header.getValue());
                    }
                })
                .endpoint(endpoint)
                .jsonMapper(new McpGsonJsonMapper())
                .build();

        return buildMcpClient(name, transport);
    }

    public static McpServerConnection connectSse(String name, String url, String endpoint, Map<String, String> headers) {
        McpClientTransport transport = HttpClientSseClientTransport
                .builder(url)
                .httpRequestCustomizer((builder, method, endpoint1, body, context) -> {
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        builder.header(header.getKey(), header.getValue());
                    }
                })
                .sseEndpoint(endpoint)
                .jsonMapper(new McpGsonJsonMapper())
                .build();

        return buildMcpClient(name, transport);
    }

    public String getName()                     { return name; }
    public List<McpToolAdapter> getToolAdapters() { return toolAdapters; }

    @Override
    public void close() {
        try {
            client.closeGracefully();
            ILog.d(TAG, "Disconnected MCP server '" + name + "'");
        } catch (Exception e) {
            ILog.d(TAG, "Error closing MCP server '" + name + "': " + e.getMessage());
        }
    }

    private static McpServerConnection buildMcpClient(String name, McpClientTransport transport) {
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("edge-ai-gateway", "1.0"))
                .jsonSchemaValidator(new McpGsonJsonSchemaValidatorSupplier().get())
                .build();

        client.initialize();
        List<McpSchema.Tool> tools = client.listTools().tools();
        List<McpToolAdapter> adapters = tools.stream()
                .map(t -> new McpToolAdapter(client, t))
                .toList();

        ILog.d(TAG, "Connected MCP server '" + name + "' — " + tools.size() + " tool(s): "
                + tools.stream().map(McpSchema.Tool::name).toList());

        return new McpServerConnection(name, client, adapters);
    }
}
