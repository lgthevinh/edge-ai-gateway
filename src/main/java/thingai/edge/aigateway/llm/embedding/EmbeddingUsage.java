package thingai.edge.aigateway.llm.embedding;

import com.google.gson.annotations.SerializedName;

public class EmbeddingUsage {
    @SerializedName("prompt_tokens")
    private int promptTokens;

    @SerializedName("total_tokens")
    private int totalTokens;

    public EmbeddingUsage() {
    }

    public EmbeddingUsage(int promptTokens, int totalTokens) {
        this.promptTokens = promptTokens;
        this.totalTokens = totalTokens;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }
}
