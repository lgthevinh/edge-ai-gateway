package thingai.edge.aigateway.llm.content;

import org.thingai.base.utils.ArrayUtils;
import thingai.edge.aigateway.llm.message.Message;
import thingai.edge.aigateway.llm.message.MessageRole;

public class Content {
    private Message[] history;
    private String input;
    private double temperature;

    public Content() {
    }

    public Content(Message[] history, String input, double temperature) {
        this.history = history;
        this.input = input;
        this.temperature = temperature;
    }

    public Message[] getHistory() {
        return history;
    }

    public void setHistory(Message[] history) {
        this.history = history;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public Message[] getMessages() {
        return ArrayUtils.append(history, new Message(MessageRole.USER, input));
    }
}
