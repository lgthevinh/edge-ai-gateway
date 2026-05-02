package thingai.edge.aigateway.agent.preset;

import thingai.edge.aigateway.agent.Agent;
import thingai.edge.aigateway.llm.LlmProvider;

public class AssistantAgent {

    public static Agent create(LlmProvider llmProvider) {
        return new Agent.Builder()
                .name("Assistant")
                .systemInstruction("You are a helpful assistant.")
                .llmProvider(llmProvider)
                .temperature(0.7)
                .build();
    }
}
