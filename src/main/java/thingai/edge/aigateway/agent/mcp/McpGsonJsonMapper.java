package thingai.edge.aigateway.agent.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import org.thingai.base.log.ILog;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Gson-backed implementation of the MCP SDK's {@link McpJsonMapper} interface.
 * Implements all read/write/convert overloads so no Jackson dependency is required.
 */
public class McpGsonJsonMapper implements McpJsonMapper {
    private static final String TAG = "GsonMcpJsonMapper";

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(McpSchema.Content.class, new ContentDeserializer())
            .registerTypeAdapter(McpSchema.ResourceContents.class, new ResourceContentsDeserializer())
            .create();

    // ── Write ─────────────────────────────────────────────────────────────────

    @Override
    public String writeValueAsString(Object value) {
        return gson.toJson(value);
    }

    @Override
    public byte[] writeValueAsBytes(Object value) {
        return gson.toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    // ── Read — String source ──────────────────────────────────────────────────

    @Override
    public <T> T readValue(String content, TypeRef<T> typeRef) {
        try {
            return gson.fromJson(content, typeRef.getType());
        } catch (RuntimeException e) {
            logJsonError("readValue(String, TypeRef)", typeRef.getType().getTypeName(), content, e);
            throw e;
        }
    }

    @Override
    public <T> T readValue(String content, Class<T> valueType) {
        try {
            return gson.fromJson(content, valueType);
        } catch (RuntimeException e) {
            logJsonError("readValue(String, Class)", valueType.getName(), content, e);
            throw e;
        }
    }

    // ── Read — byte[] source ──────────────────────────────────────────────────

    @Override
    public <T> T readValue(byte[] content, TypeRef<T> typeRef) {
        return readValue(new String(content, StandardCharsets.UTF_8), typeRef);
    }

    @Override
    public <T> T readValue(byte[] content, Class<T> valueType) {
        return readValue(new String(content, StandardCharsets.UTF_8), valueType);
    }

    // ── Convert ───────────────────────────────────────────────────────────────

    @Override
    public <T> T convertValue(Object fromValue, TypeRef<T> typeRef) {
        String json = gson.toJson(fromValue);
        try {
            return gson.fromJson(json, typeRef.getType());
        } catch (RuntimeException e) {
            logJsonError("convertValue(Object, TypeRef)", typeRef.getType().getTypeName(), json, e);
            throw e;
        }
    }

    @Override
    public <T> T convertValue(Object fromValue, Class<T> toValueType) {
        String json = gson.toJson(fromValue);
        try {
            return gson.fromJson(json, toValueType);
        } catch (RuntimeException e) {
            logJsonError("convertValue(Object, Class)", toValueType.getName(), json, e);
            throw e;
        }
    }

    private void logJsonError(String operation, String targetType, String json, RuntimeException e) {
        ILog.d(TAG, "logJsonError", "json_failed operation=" + operation
                + ", targetType=" + targetType
                + ", error=" + e.getMessage()
                + "\nJSON: " + truncate(json)
                + "\n" + stackTrace(e));
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 4000) return value;
        return value.substring(0, 4000) + "\n[TRUNCATED " + (value.length() - 4000) + " chars]";
    }

    private String stackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static class ContentDeserializer implements JsonDeserializer<McpSchema.Content> {
        @Override
        public McpSchema.Content deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            if (json == null || !json.isJsonObject()) {
                throw new JsonParseException("MCP content must be a JSON object");
            }

            JsonObject obj = json.getAsJsonObject();
            String type = stringValue(obj, "type");
            McpSchema.Annotations annotations = context.deserialize(obj.get("annotations"), McpSchema.Annotations.class);
            Map<String, Object> meta = context.deserialize(obj.get("_meta"), MAP_TYPE);

            if ("text".equals(type) || (type == null && obj.has("text"))) {
                return new McpSchema.TextContent(annotations, stringValue(obj, "text"), meta);
            }
            if ("image".equals(type)) {
                return new McpSchema.ImageContent(annotations, stringValue(obj, "data"), stringValue(obj, "mimeType"), meta);
            }
            if ("audio".equals(type)) {
                return new McpSchema.AudioContent(annotations, stringValue(obj, "data"), stringValue(obj, "mimeType"), meta);
            }
            if ("resource".equals(type)) {
                McpSchema.ResourceContents resource = context.deserialize(
                        obj.get("resource"),
                        McpSchema.ResourceContents.class
                );
                return new McpSchema.EmbeddedResource(annotations, resource, meta);
            }
            if ("resource_link".equals(type)) {
                return new McpSchema.ResourceLink(
                        stringValue(obj, "name"),
                        stringValue(obj, "title"),
                        stringValue(obj, "uri"),
                        stringValue(obj, "description"),
                        stringValue(obj, "mimeType"),
                        longValue(obj, "size"),
                        annotations,
                        meta
                );
            }

            throw new JsonParseException("Unsupported MCP content type: " + type);
        }
    }

    private static class ResourceContentsDeserializer implements JsonDeserializer<McpSchema.ResourceContents> {
        @Override
        public McpSchema.ResourceContents deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            if (json == null || !json.isJsonObject()) {
                throw new JsonParseException("MCP resource contents must be a JSON object");
            }

            JsonObject obj = json.getAsJsonObject();
            Map<String, Object> meta = context.deserialize(obj.get("_meta"), MAP_TYPE);

            if (obj.has("text")) {
                return new McpSchema.TextResourceContents(
                        stringValue(obj, "uri"),
                        stringValue(obj, "mimeType"),
                        stringValue(obj, "text"),
                        meta
                );
            }
            if (obj.has("blob")) {
                return new McpSchema.BlobResourceContents(
                        stringValue(obj, "uri"),
                        stringValue(obj, "mimeType"),
                        stringValue(obj, "blob"),
                        meta
                );
            }

            throw new JsonParseException("Unsupported MCP resource contents: " + obj);
        }
    }

    private static String stringValue(JsonObject obj, String memberName) {
        JsonElement element = obj.get(memberName);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static Long longValue(JsonObject obj, String memberName) {
        JsonElement element = obj.get(memberName);
        return element == null || element.isJsonNull() ? null : element.getAsLong();
    }
}
