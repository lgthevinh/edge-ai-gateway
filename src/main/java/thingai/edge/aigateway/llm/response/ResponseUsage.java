package thingai.edge.aigateway.llm.response;

import com.google.gson.annotations.SerializedName;

public class ResponseUsage {
    @SerializedName("prompt_tokens")
    private int promptTokens;

    @SerializedName("completion_tokens")
    private int completionTokens;

    @SerializedName("total_tokens")
    private int totalTokens;

    @SerializedName("prompt_per_second")
    private Double promptPerSecond;

    @SerializedName("predicted_per_second")
    private Double predictedPerSecond;

    public ResponseUsage() {
    }

    public ResponseUsage(int promptTokens, int completionTokens, int totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Double getPromptPerSecond() {
        return promptPerSecond;
    }

    public void setPromptPerSecond(Double promptPerSecond) {
        this.promptPerSecond = promptPerSecond;
    }

    public Double getPredictedPerSecond() {
        return predictedPerSecond;
    }

    public void setPredictedPerSecond(Double predictedPerSecond) {
        this.predictedPerSecond = predictedPerSecond;
    }

    public void applyTimings(ResponseTimings timings) {
        if (timings == null) return;
        if (timings.getPromptPerSecond() != null) {
            promptPerSecond = timings.getPromptPerSecond();
        }
        if (timings.getPredictedPerSecond() != null) {
            predictedPerSecond = timings.getPredictedPerSecond();
        }
    }
}
