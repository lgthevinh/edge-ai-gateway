package thingai.edge.aigateway.llm.response;

import com.google.gson.annotations.SerializedName;
import thingai.edge.aigateway.llm.message.Message;

public class ResponseChoice {
    @SerializedName("message")
    private Message message;

    @SerializedName("finish_reason")
    private String finishReason;

    public ResponseChoice() {
    }

    public ResponseChoice(Message message, String finishReason) {
        this.message = message;
        this.finishReason = finishReason;
    }

    public Message getMessage() {
        return message;
    }

    public void setMessage(Message message) {
        this.message = message;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }
}
