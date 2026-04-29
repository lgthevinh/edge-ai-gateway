package thingai.edge.aigateway.agent;

import thingai.edge.aigateway.llm.LlmProvider;

public interface IAgent {
    String getName();
    String getSystemInstruction();
    LlmProvider getLlmProvider();
    IAgentTool[] getTools();
    double getTemperature();
}
