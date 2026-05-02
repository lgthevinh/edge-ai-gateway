package thingai.edge.aigateway.agent.preset;

import thingai.edge.aigateway.agent.Agent;
import thingai.edge.aigateway.agent.tools.CurlApiTool;
import thingai.edge.aigateway.agent.tools.ListFilesTool;
import thingai.edge.aigateway.agent.tools.ReadFileTool;
import thingai.edge.aigateway.llm.LlmProvider;

public class ResearchAgent {

    public static Agent create(LlmProvider llmProvider) {
        return new Agent.Builder()
                .name("Research")
                .systemInstruction("You are a research assistant. Use your tools to read files, list directories, and fetch data from APIs to answer questions.")
                .llmProvider(llmProvider)
                .tools(new ReadFileTool(), new ListFilesTool(), new CurlApiTool())
                .temperature(0.3)
                .build();
    }
}
