package thingai.edge.aigateway.handler.embedding;

import org.thingai.base.log.ILog;
import thingai.edge.aigateway.llm.LlmProvider;
import thingai.edge.aigateway.llm.embedding.EmbeddingRequest;
import thingai.edge.aigateway.llm.embedding.EmbeddingResponse;

public class EmbeddingHandler {
    private static final String TAG = "EmbeddingHandler";

    private final LlmProvider embeddingProvider;
    private final String embeddingModel;
    private final int embeddingDimensions;

    public EmbeddingHandler(LlmProvider embeddingProvider, String embeddingModel, int embeddingDimensions) {
        if (embeddingProvider == null) {
            throw new IllegalArgumentException("embeddingProvider is required");
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("embeddingModel is required");
        }
        this.embeddingProvider = embeddingProvider;
        this.embeddingModel = embeddingModel;
        this.embeddingDimensions = Math.max(0, embeddingDimensions);
    }

    public float[] embed(String input) {
        if (input == null || input.isBlank()) return null;
        float[] embedding = requestEmbedding(input.trim());
        if (embedding == null || embedding.length == 0) {
            ILog.d(TAG, "empty embedding for input");
            return null;
        }
        validateDimensions(embedding);
        return embedding;
    }

    public float[][] embed(String[] inputs) {
        if (inputs == null || inputs.length == 0) return new float[0][];
        EmbeddingRequest request = EmbeddingRequest.of(inputs);
        request.setModel(embeddingModel);
        if (embeddingDimensions > 0) {
            request.setDimensions(embeddingDimensions);
        }

        EmbeddingResponse response = embeddingProvider.embeddings(request);
        if (response == null || response.getData() == null) return new float[0][];

        float[][] embeddings = new float[response.getData().length][];
        for (int i = 0; i < response.getData().length; i++) {
            embeddings[i] = response.getData()[i].getEmbedding();
            validateDimensions(embeddings[i]);
        }
        return embeddings;
    }

    private float[] requestEmbedding(String input) {
        EmbeddingRequest request = EmbeddingRequest.of(input);
        request.setModel(embeddingModel);
        if (embeddingDimensions > 0) {
            request.setDimensions(embeddingDimensions);
        }

        EmbeddingResponse response = embeddingProvider.embeddings(request);
        if (response == null) return null;
        return response.getFirstEmbedding();
    }

    private void validateDimensions(float[] embedding) {
        if (embeddingDimensions > 0 && embedding != null && embedding.length != embeddingDimensions) {
            throw new IllegalStateException("Embedding dimension mismatch. expected="
                    + embeddingDimensions + " actual=" + embedding.length);
        }
    }
}
