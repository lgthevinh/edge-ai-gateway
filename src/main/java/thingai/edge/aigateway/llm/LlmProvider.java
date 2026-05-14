package thingai.edge.aigateway.llm;

import thingai.edge.aigateway.llm.content.Content;
import thingai.edge.aigateway.llm.embedding.EmbeddingRequest;
import thingai.edge.aigateway.llm.embedding.EmbeddingResponse;
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

    public EmbeddingResponse embeddings(EmbeddingRequest request) {
        throw new UnsupportedOperationException("Embedding is not supported by this LLM provider");
    }

    public EmbeddingResponse embeddings(String input) {
        return embeddings(EmbeddingRequest.of(input));
    }

    public EmbeddingResponse embeddings(String[] input) {
        return embeddings(EmbeddingRequest.of(input));
    }
}
