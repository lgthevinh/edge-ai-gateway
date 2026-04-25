package thingai.edge.aigateway.llm.response;

import thingai.edge.aigateway.llm.message.Message;

public class ResponseChoice {
    private Message message;
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
