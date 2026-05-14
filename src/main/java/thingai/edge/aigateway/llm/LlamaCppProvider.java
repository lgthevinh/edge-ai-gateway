package thingai.edge.aigateway.llm;

import com.google.gson.JsonObject;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.llm.content.Content;
import thingai.edge.aigateway.llm.embedding.EmbeddingRequest;
import thingai.edge.aigateway.llm.embedding.EmbeddingResponse;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.message.MessageRole;
import thingai.edge.aigateway.llm.message.ToolCall;
import thingai.edge.aigateway.llm.message.ToolCallFunction;
import thingai.edge.aigateway.llm.response.Response;
import thingai.edge.aigateway.llm.response.ResponseChoice;
import thingai.edge.aigateway.llm.response.ResponseStreamCallback;
import thingai.edge.aigateway.llm.response.ResponseTimings;
import thingai.edge.aigateway.llm.response.ResponseUsage;
import thingai.edge.aigateway.utils.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

public class LlamaCppProvider extends LlmProvider {
    private static final String TAG = "LlamaCppProvider";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String apiKey;
    private final String baseModel;

    public LlamaCppProvider(String baseUrl, String apiKey, String baseModel) {
        super(baseUrl);
        this.apiKey = apiKey;
        this.baseModel = baseModel;
    }

    @Override
    public Response chatCompletion(Content content) {
        ILog.d(TAG, "chatCompletion");
        HttpRequest request = buildRequest(content, false);
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ILog.d(TAG, "chatCompletion", "response: " + response.body());
            Response result = JsonUtil.fromJson(response.body(), Response.class);
            applyTimings(result);
            return result;
        } catch (Exception e) {
            ILog.d(TAG, e.getMessage());
            return null;
        }
    }

    @Override
    public CompletableFuture<Response> chatCompletionAsync(Content content, ResponseStreamCallback callback) {
        ILog.d(TAG, "chatCompletionAsync");
        HttpRequest request = buildRequest(content, true);
        StringBuilder fullText = new StringBuilder();
        AtomicReference<ResponseUsage> usage = new AtomicReference<>();
        AtomicReference<String> finishReason = new AtomicReference<>("stop");
        TreeMap<Integer, StreamingToolCall> toolCalls = new TreeMap<>();
        CompletableFuture<Response> promise = new CompletableFuture<>();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> response.body().forEach(line -> {
                    if (!line.startsWith("data: ")) return;
                    String data = line.substring(6).trim();

                    if (data.equals("[DONE]")) {
                        String text = fullText.toString();
                        ILog.d(TAG, "chatCompletionAsync", "text: " + text.trim());
                        callback.onComplete(text);
                        promise.complete(buildResponse(text, finishReason.get(), usage.get(), toToolCalls(toolCalls)));
                        return;
                    }

                    try {
                        JsonObject obj = JsonUtil.fromJson(data, JsonObject.class);
                        if (obj.has("usage") && obj.get("usage").isJsonObject()) {
                            ResponseUsage responseUsage = JsonUtil.fromJson(obj.get("usage").toString(), ResponseUsage.class);
                            if (obj.has("timings") && obj.get("timings").isJsonObject()) {
                                responseUsage.applyTimings(JsonUtil.fromJson(obj.get("timings").toString(), ResponseTimings.class));
                            }
                            usage.set(responseUsage);
                            callback.onUsage(responseUsage);
                        } else if (obj.has("timings") && obj.get("timings").isJsonObject()) {
                            ResponseUsage responseUsage = usage.updateAndGet(existing -> {
                                ResponseUsage current = existing != null ? existing : new ResponseUsage();
                                current.applyTimings(JsonUtil.fromJson(obj.get("timings").toString(), ResponseTimings.class));
                                return current;
                            });
                            callback.onUsage(responseUsage);
                        }
                        if (!obj.has("choices") || obj.getAsJsonArray("choices").isEmpty()) return;
                        JsonObject choice = obj.getAsJsonArray("choices").get(0).getAsJsonObject();
                        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                            finishReason.set(choice.get("finish_reason").getAsString());
                        }
                        if (!choice.has("delta") || !choice.get("delta").isJsonObject()) return;
                        JsonObject delta = choice.getAsJsonObject("delta");
                        if (delta.has("content") && !delta.get("content").isJsonNull()) {
                            String token = delta.get("content").getAsString();
                            fullText.append(token);
                            callback.onToken(token);
                        }
                        if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                            mergeToolCalls(toolCalls, delta.getAsJsonArray("tool_calls"));
                        }
                    } catch (Exception e) {
                        ILog.d(TAG, "chatCompletionAsync: " + e.getMessage());
                    }
                }))
                .exceptionally(e -> {
                    Exception ex = new Exception(e);
                    callback.onError(ex);
                    promise.completeExceptionally(ex);
                    return null;
                });

        return promise;
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest requestBody) {
        ILog.d(TAG, "embeddings");
        HttpRequest request = buildEmbeddingRequest(requestBody);
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ILog.d(TAG, "embeddings", "response: " + response.body());
            return JsonUtil.fromJson(response.body(), EmbeddingResponse.class);
        } catch (Exception e) {
            ILog.d(TAG, "embeddings failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean healthCheck() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            JsonObject obj = JsonUtil.fromJson(response.body(), JsonObject.class);
            return obj != null && "ok".equals(obj.get("status").getAsString());
        } catch (Exception e) {
            ILog.d(TAG, "healthCheck failed: " + e.getMessage());
            return false;
        }
    }

    private HttpRequest buildRequest(Content content, boolean stream) {
        Map<String, Object> map = new HashMap<>();
        map.put("model", baseModel);
        map.put("messages", content.getMessages());
        map.put("temperature", content.getTemperature());
        map.put("stream", stream);
        if (stream) {
            map.put("stream_options", Map.of("include_usage", true));
        }
        if (content.getTools() != null && content.getTools().length > 0) {
            map.put("tools", content.getTools());
            map.put("tool_choice", content.getToolChoice());
        }
        String json = JsonUtil.toJson(map);
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private HttpRequest buildEmbeddingRequest(EmbeddingRequest requestBody) {
        if (requestBody.getModel() == null || requestBody.getModel().isBlank()) {
            requestBody.setModel(baseModel);
        }
        if (requestBody.getEncodingFormat() == null || requestBody.getEncodingFormat().isBlank()) {
            requestBody.setEncodingFormat("float");
        }

        String json = JsonUtil.toJson(requestBody);
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/embeddings"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private Response buildResponse(String fullText, String finishReason, ResponseUsage usage, ToolCall[] toolCalls) {
        Message message = new Message(MessageRole.MODEL, fullText);
        if (toolCalls != null && toolCalls.length > 0) message.setToolCalls(toolCalls);
        ResponseChoice choice = new ResponseChoice(message, finishReason != null ? finishReason : "stop");
        return new Response(new ResponseChoice[]{choice}, usage);
    }

    private void applyTimings(Response response) {
        if (response == null || response.getTimings() == null) return;
        ResponseUsage usage = response.getUsage();
        if (usage == null) {
            usage = new ResponseUsage();
            response.setUsage(usage);
        }
        usage.applyTimings(response.getTimings());
    }

    private void mergeToolCalls(TreeMap<Integer, StreamingToolCall> accumulated, com.google.gson.JsonArray deltas) {
        for (int i = 0; i < deltas.size(); i++) {
            if (!deltas.get(i).isJsonObject()) continue;
            JsonObject item = deltas.get(i).getAsJsonObject();
            int index = item.has("index") && !item.get("index").isJsonNull()
                    ? item.get("index").getAsInt()
                    : accumulated.size();
            StreamingToolCall call = accumulated.computeIfAbsent(index, ignored -> new StreamingToolCall());

            if (item.has("id") && !item.get("id").isJsonNull()) call.id = item.get("id").getAsString();
            if (item.has("type") && !item.get("type").isJsonNull()) call.type = item.get("type").getAsString();
            if (item.has("function") && item.get("function").isJsonObject()) {
                JsonObject function = item.getAsJsonObject("function");
                if (function.has("name") && !function.get("name").isJsonNull()) {
                    call.name = function.get("name").getAsString();
                }
                if (function.has("arguments") && !function.get("arguments").isJsonNull()) {
                    call.arguments.append(function.get("arguments").getAsString());
                }
            }
        }
    }

    private ToolCall[] toToolCalls(TreeMap<Integer, StreamingToolCall> accumulated) {
        if (accumulated.isEmpty()) return null;
        ToolCall[] result = new ToolCall[accumulated.size()];
        int i = 0;
        for (Entry<Integer, StreamingToolCall> entry : accumulated.entrySet()) {
            StreamingToolCall call = entry.getValue();
            result[i++] = new ToolCall(
                    call.id,
                    call.type != null ? call.type : "function",
                    new ToolCallFunction(call.name, call.arguments.toString())
            );
        }
        return result;
    }

    private static class StreamingToolCall {
        private String id;
        private String type;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
