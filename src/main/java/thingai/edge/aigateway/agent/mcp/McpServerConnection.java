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
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the lifecycle of a single MCP server process connected via stdio.
 * Spawns the process, initializes the protocol handshake, fetches the tool list,
 * and exposes each tool as a {@link McpToolAdapter}.
 */
public class McpServerConnection implements Closeable {

    private static final String TAG = "McpServerConnection";
    private static final ExecutorService MCP_HTTP_EXECUTOR = Executors.newFixedThreadPool(
            4,
            daemonThreadFactory("mcp-http")
    );

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
        return connectStdio(name, command, args, inferFilesystemDefaultPath(command, args));
    }

    public static McpServerConnection connectStdio(String name, String command, List<String> args, String defaultPath) {
        ServerParameters params = ServerParameters.builder(command)
                .args(args)
                .build();

        // StdioClientTransport takes ServerParameters and our Gson-backed mapper
        StdioClientTransport transport = new StdioClientTransport(params, new McpGsonJsonMapper());

        return buildMcpClient(name, transport, defaultPath);
    }

    public static McpServerConnection connectHttp(String name, String url, String endpoint, boolean useUrlAsEndpoint, Map<String, String> headers) {
        HttpEndpoint httpEndpoint = resolveHttpEndpoint(url, endpoint, useUrlAsEndpoint);
        McpClientTransport transport = HttpClientStreamableHttpTransport
                .builder(httpEndpoint.baseUri())
                .endpoint(httpEndpoint.endpoint())
                .customizeClient(builder -> builder.executor(MCP_HTTP_EXECUTOR))
                .httpRequestCustomizer((builder, method, endpoint1, body, context) -> {
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        builder.header(header.getKey(), header.getValue());
                    }
                    builder.timeout(Duration.ofSeconds(30L));
                })
                .jsonMapper(new McpGsonJsonMapper())
                .build();

        return buildMcpClient(name, transport, null);
    }

    public static McpServerConnection connectSse(String name, String url, Map<String, String> headers) {
        return connectSse(name, url, "/sse", false, headers);
    }

    public static McpServerConnection connectSse(String name, String url, String endpoint,
                                                 boolean useUrlAsEndpoint, Map<String, String> headers) {
        HttpEndpoint httpEndpoint = resolveHttpEndpoint(url, endpoint, useUrlAsEndpoint);
        McpClientTransport transport = HttpClientSseClientTransport
                .builder(httpEndpoint.baseUri())
                .sseEndpoint(httpEndpoint.endpoint())
                .customizeClient(builder -> builder.executor(MCP_HTTP_EXECUTOR))
                .httpRequestCustomizer((builder, method, endpoint1, body, context) -> {
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        builder.header(header.getKey(), header.getValue());
                    }
                })
                .jsonMapper(new McpGsonJsonMapper())
                .build();

        return buildMcpClient(name, transport, null);
    }

    public String getName()                     { return name; }
    public List<McpToolAdapter> getToolAdapters() { return toolAdapters; }

    @Override
    public void close() {
        ILog.d(TAG, "close");
        try {
            client.closeGracefully();
            ILog.d(TAG, "Disconnected MCP server '" + name + "'");
        } catch (Exception e) {
            ILog.d(TAG, "Error closing MCP server '" + name + "': " + e.getMessage());
        }
    }

    private static McpServerConnection buildMcpClient(String name, McpClientTransport transport, String defaultPath) {
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("edge-ai-gateway", "1.0"))
                .jsonSchemaValidator(new McpGsonJsonSchemaValidatorSupplier().get())
                .build();

        client.initialize();
        List<McpSchema.Tool> tools = client.listTools().tools();
        List<McpToolAdapter> adapters = tools.stream()
                .map(t -> new McpToolAdapter(client, t, defaultPath))
                .toList();

        ILog.d(TAG, "buildMcpClient '" + name + "' — " + tools.size() + " tool(s): "
                + tools.stream().map(McpSchema.Tool::name).toList());

        return new McpServerConnection(name, client, adapters);
    }

    static String inferFilesystemDefaultPath(String command, List<String> args) {
        boolean filesystemServer = command != null && command.contains("server-filesystem");
        int packageIndex = -1;
        for (int i = 0; args != null && i < args.size(); i++) {
            String arg = args.get(i);
            if (arg != null && arg.contains("server-filesystem")) {
                filesystemServer = true;
                packageIndex = i;
                break;
            }
        }
        if (!filesystemServer || args == null) return null;

        int start = packageIndex >= 0 ? packageIndex + 1 : 0;
        for (int i = start; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg != null && !arg.isBlank() && !arg.startsWith("-")) {
                return arg;
            }
        }
        return null;
    }

    private static HttpEndpoint resolveHttpEndpoint(String url, String endpoint, boolean useUrlAsEndpoint) {
        URI uri = URI.create(url);
        if (!useUrlAsEndpoint) {
            return new HttpEndpoint(stripTrailingSlash(url), ensureLeadingSlash(endpoint));
        }

        String baseUri = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            path = "/";
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            path += "?" + uri.getRawQuery();
        }
        return new HttpEndpoint(baseUri, path);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.length() <= 1) return value;
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String ensureLeadingSlash(String value) {
        if (value == null || value.isBlank()) return "/";
        String endpoint = value.trim();
        return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    }

    private record HttpEndpoint(String baseUri, String endpoint) {}

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
