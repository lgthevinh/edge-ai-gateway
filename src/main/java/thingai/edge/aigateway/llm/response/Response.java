package thingai.edge.aigateway.llm.response;

import com.google.gson.annotations.SerializedName;

public class Response {
    @SerializedName("choices")
    private ResponseChoice[] choices;

    @SerializedName("usage")
    private ResponseUsage usage;

    @SerializedName("timings")
    private ResponseTimings timings;

    public Response() {
    }

    public Response(ResponseChoice[] choices, ResponseUsage usage) {
        this.choices = choices;
        this.usage = usage;
    }

    public ResponseChoice[] getChoices() {
        return choices;
    }

    public void setChoices(ResponseChoice[] choices) {
        this.choices = choices;
    }

    public ResponseUsage getUsage() {
        return usage;
    }

    public void setUsage(ResponseUsage usage) {
        this.usage = usage;
    }

    public ResponseTimings getTimings() {
        return timings;
    }

    public void setTimings(ResponseTimings timings) {
        this.timings = timings;
    }

    public String getMessageContent() {
        if (choices != null && choices.length > 0) {
            return choices[0].getMessage().getContent();
        }
        return null;
    }
}
