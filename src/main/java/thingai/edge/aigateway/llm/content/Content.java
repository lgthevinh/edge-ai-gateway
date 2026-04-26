package thingai.edge.aigateway.llm.content;

import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.message.MessageRole;

public class Content {
    private Message[] messages;
    private double temperature;

    public Content() {
    }

    public Content(Message[] history, String input, double temperature) {
        Message userMsg = new Message(MessageRole.USER, input);
        if (history == null || history.length == 0) {
            this.messages = new Message[]{userMsg};
        } else {
            this.messages = new Message[history.length + 1];
            System.arraycopy(history, 0, this.messages, 0, history.length);
            this.messages[history.length] = userMsg;
        }
        this.temperature = temperature;
    }

    public Message[] getMessages() {
        return messages;
    }

    public void setMessages(Message[] messages) {
        this.messages = messages;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
