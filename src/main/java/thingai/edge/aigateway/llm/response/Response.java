package thingai.edge.aigateway.llm.response;

public class Response {
    private ResponseChoice[] choices;
    private ResponseUsage usage;

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

    public String getMessageContent() {
        if (choices != null && choices.length > 0) {
            return choices[0].getMessage().getContent();
        }
        return null;
    }
}
