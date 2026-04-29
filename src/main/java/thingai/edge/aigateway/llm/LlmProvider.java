package thingai.edge.aigateway.llm;

import thingai.edge.aigateway.llm.content.Content;
import thingai.edge.aigateway.llm.response.Response;
import thingai.edge.aigateway.llm.response.ResponseStreamCallback;

import java.util.concurrent.CompletableFuture;

public abstract class LlmProvider {
    protected final String baseUrl;

    protected LlmProvider(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public abstract Response chatCompletion(Content content);
    public abstract CompletableFuture<Response> chatCompletionAsync(Content content, ResponseStreamCallback callback);
    public abstract boolean healthCheck();
}
