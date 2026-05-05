package thingai.edge.aigateway.agent.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.agent.IAgentTool;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

/**
 * Adapts a single MCP tool ({@link McpSchema.Tool}) into the agent's
 * {@link IAgentTool} interface so it can be registered on an {@link thingai.edge.aigateway.agent.Agent}
 * alongside built-in tools (read_file, list_files, etc.).
 */
public class McpToolAdapter implements IAgentTool {
    private static final String TAG = "McpToolAdapter";

    private final McpSyncClient client;
    private final McpSchema.Tool tool;
    private final Gson gson = new Gson();

    public McpToolAdapter(McpSyncClient client, McpSchema.Tool tool) {
        this.client = client;
        this.tool = tool;
    }

    @Override
    public String getName() {
        return tool.name();
    }

    @Override
    public String getDescription() {
        return tool.description() != null ? tool.description() : "";
    }

    /**
     * Builds an OpenAI-compatible JSON schema string from the MCP tool's inputSchema.
     * Manually reads the individual fields (type, properties, required) via accessor methods
     * to avoid relying on Gson to serialize a potentially Jackson-annotated SDK class.
     */
    @Override
    public String getParametersJson() {
        McpSchema.JsonSchema input = tool.inputSchema();
        JsonObject schema = new JsonObject();

        // type — default to "object" if not specified
        schema.addProperty("type", input != null && input.type() != null ? input.type() : "object");

        // properties — may be Map<String, Object> or Map<String, McpSchema.JsonSchema>
        if (input != null && input.properties() != null) {
            schema.add("properties", gson.toJsonTree(input.properties()));
        } else {
            schema.add("properties", new JsonObject());
        }

        // required — only include if non-empty
        if (input != null && input.required() != null && !input.required().isEmpty()) {
            schema.add("required", gson.toJsonTree(input.required()));
        }

        return gson.toJson(schema);
    }

    /**
     * Executes the MCP tool by calling the remote server.
     *
     * @param paramsJson JSON string of arguments (Gson-serialized Map)
     * @return JSON string result from the server, or an error object
     */
    @Override
    public String execute(String paramsJson) {
        ILog.d(TAG, "execute", "call_start tool='" + tool.name() + "', params=" + paramsJson);
        try {
            Map<String, Object> args = gson.fromJson(
                    paramsJson,
                    new TypeToken<Map<String, Object>>() {}.getType()
            );
            ILog.d(TAG, "execute", "args_parsed tool='" + tool.name() + "', args=" + gson.toJson(args));

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(tool.name(), args)
            );
            ILog.d(TAG, "execute", "call_result tool='" + tool.name() + "', isError="
                    + result.isError()
                    + ", contentCount=" + (result.content() != null ? result.content().size() : 0)
                    + ", structuredContent=" + gson.toJson(result.structuredContent()));

            logContentItems(result.content());

            // Check for server-side error flag
            if (Boolean.TRUE.equals(result.isError())) {
                JsonObject error = new JsonObject();
                error.addProperty("error", extractText(result.content()));
                ILog.d(TAG, "execute", "return_error tool='" + tool.name() + "', error=" + error);
                return gson.toJson(error);
            }

            JsonObject out = new JsonObject();
            out.addProperty("result", extractText(result.content()));
            ILog.d(TAG, "execute", "return_result tool='" + tool.name() + "', result=" + out);
            return gson.toJson(out);

        } catch (Exception e) {
            ILog.d(TAG, "execute", "call_failed tool='" + tool.name() + "', error=" + e.getMessage() + "\n" + stackTrace(e));
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage() != null ? e.getMessage() : "unknown error");
            return gson.toJson(error);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String extractText(List<McpSchema.Content> content) {
        if (content == null || content.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content item : content) {
            if (item instanceof McpSchema.TextContent text) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    private void logContentItems(List<McpSchema.Content> content) {
        if (content == null) {
            ILog.d(TAG, "logContentItems", "content_null tool='" + tool.name() + "'");
            return;
        }
        for (int i = 0; i < content.size(); i++) {
            McpSchema.Content item = content.get(i);
            ILog.d(TAG, "logContentItems", "content_item tool='" + tool.name() + "', index=" + i + ", type="
                    + (item != null ? item.getClass().getName() : "null")
                    + ", value=" + gson.toJson(item));
        }
    }

    private String stackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
