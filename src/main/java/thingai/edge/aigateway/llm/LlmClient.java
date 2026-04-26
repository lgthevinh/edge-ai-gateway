package thingai.edge.aigateway.llm;

import com.google.gson.JsonObject;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.llm.message.MessageStreamCallback;
import thingai.edge.aigateway.llm.message.MessageRole;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.response.Response;
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

    public Response chatCompletion(Message[] history, String prompt) {
        ILog.d(TAG, "chatCompletion");
        // build messages array
        Message[] message;
        if (history == null) {
            message = new Message[]{new Message(MessageRole.USER, prompt)};
        } else {
            message = new Message[history.length + 1];
            System.arraycopy(history, 0, message, 0, history.length);
            message[history.length] = new Message(MessageRole.USER, prompt);
        }

        // build payload
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

        // call llm
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return JsonUtil.fromJson(response.body(), Response.class);
        } catch (Exception e) {
            ILog.d(TAG, e.getMessage());
            return null;
        }
    }

    public void chatCompletionAsync(Message[] history, String prompt, MessageStreamCallback callback) {

    }

    public boolean healthCheck() {
        return true;
    }

    public void close() {

    }
}
