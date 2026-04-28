package thingai.edge.aigateway.llm;

import com.google.gson.JsonObject;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.llm.content.Content;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.message.MessageRole;
import thingai.edge.aigateway.llm.response.Response;
import thingai.edge.aigateway.llm.response.ResponseChoice;
import thingai.edge.aigateway.llm.response.ResponseStreamCallback;
import thingai.edge.aigateway.utils.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;


public class LlmClient {
    private static final String TAG = "LlmClient";

    private static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private final String baseUrl;
    private final String apiKey;
    private final String baseModel;

    public LlmClient(String baseUrl, String apiKey, String baseModel) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.baseModel = baseModel;
    }

    public Response chatCompletion(Content content) {
        ILog.d(TAG, "chatCompletion");
        HttpRequest request = buildRequest(content, false);
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return JsonUtil.fromJson(response.body(), Response.class);
        } catch (Exception e) {
            ILog.d(TAG, e.getMessage());
            return null;
        }
    }

    public CompletableFuture<Response> chatCompletionAsync(Content content, ResponseStreamCallback callback) {
        ILog.d(TAG, "chatCompletionAsync");
        HttpRequest request = buildRequest(content, true);
        StringBuilder fullText = new StringBuilder();
        CompletableFuture<Response> promise = new CompletableFuture<>();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> response.body().forEach(line -> {
                    if (!line.startsWith("data: ")) return;
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) {
                        String text = fullText.toString();
                        callback.onComplete(text);
                        promise.complete(buildResponse(text));
                        return;
                    }
                    try {
                        JsonObject obj = JsonUtil.fromJson(data, JsonObject.class);
                        var delta = obj.getAsJsonArray("choices")
                                .get(0).getAsJsonObject()
                                .getAsJsonObject("delta");
                        if (delta.has("content") && !delta.get("content").isJsonNull()) {
                            String token = delta.get("content").getAsString();
                            fullText.append(token);
                            callback.onToken(token);
                        }
                    } catch (Exception ignored) {
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
        if (content.getTools() != null && content.getTools().length > 0) {
            map.put("tools", content.getTools());
            map.put("tool_choice", content.getToolChoice());
        }
        String json = JsonUtil.toJson(map);
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private Response buildResponse(String fullText) {
        ResponseChoice choice = new ResponseChoice(new Message(MessageRole.MODEL, fullText), "stop");
        return new Response(new ResponseChoice[]{choice}, null);
    }
}
