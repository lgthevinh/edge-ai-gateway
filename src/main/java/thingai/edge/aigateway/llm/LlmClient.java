package thingai.edge.aigateway.llm;

import com.google.gson.JsonObject;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.llm.message.MessageStreamCallback;
import thingai.edge.aigateway.llm.message.MessageRole;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.utils.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;


public class LlmClient {
    private static final String TAG = "LlmClient";

    private static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(300)).build();

    private final String baseUrl;
    private final String apiKey; // if using
    private final String baseModel; // any if using llama.cpp

    public LlmClient(String baseUrl, String apiKey, String baseModel) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.baseModel = baseModel;
    }

    public String sendMessage(Message[] history, String prompt) {
        Message[] message = new Message[history.length+1];
        message[history.length] = new Message(MessageRole.USER, prompt);
        return chatCompletion(message);
    }

    public void sendMessageAsync(Message[] history, String prompt, MessageStreamCallback callback) {

    }

    public boolean healthCheck() {
        return true;
    }

    public void close() {

    }

    private String chatCompletion(Message[] message) {
        ILog.d(TAG, "chatCompletion");
        Map<String, Object> map = Map.of(
                "model", baseModel,
                "messages", message,
                "temperature", 0.7,
                "stream", false
        );
        String json = JsonUtil.toJson(map);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject jsonResponse = JsonUtil.fromJson(response.body(), JsonObject.class);

            ILog.d(TAG, "chatCompletion", jsonResponse.toString());

            return jsonResponse.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (Exception e) {
            ILog.d(TAG, e.getMessage());
            return null;
        }
    }
}
