package thingai.edge.aigateway.agent.preset;

import thingai.edge.aigateway.agent.IAgent;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.llm.LlmProvider;

public class AssistantAgent implements IAgent {
    private final LlmProvider llmProvider;

    public AssistantAgent(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    @Override
    public String getName() {
        return "Assistant";
    }

    @Override
    public String getSystemInstruction() {
        return "You are a helpful assistant.";
    }

    @Override
    public LlmProvider getLlmProvider() {
        return llmProvider;
    }

    @Override
    public IAgentTool[] getTools() {
        return null;
    }

    @Override
    public double getTemperature() {
        return 0.7;
    }
}
