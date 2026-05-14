package thingai.edge.aigateway.llm.response;

import com.google.gson.annotations.SerializedName;

public class ResponseTimings {
    @SerializedName("prompt_per_second")
    private Double promptPerSecond;

    @SerializedName("predicted_per_second")
    private Double predictedPerSecond;

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
}
