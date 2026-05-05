package thingai.edge.aigateway.agent.preset;

import org.thingai.base.utils.ArrayUtils;
import thingai.edge.aigateway.agent.Agent;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.agent.tools.CurlApiTool;
import thingai.edge.aigateway.llm.LlmProvider;

public class AssistantAgent {
    public static Agent create(LlmProvider llmProvider) {
        return create(llmProvider, new IAgentTool[0]);
    }

    public static Agent create(LlmProvider llmProvider, IAgentTool... extraTools) {
        IAgentTool[] builtIn = { new CurlApiTool() };
        IAgentTool[] all = ArrayUtils.concat(builtIn, extraTools);
        return new Agent.Builder()
                .name("Assistant")
                .systemInstruction(buildSystemInstruction(all))
                .llmProvider(llmProvider)
                .tools(all)
                .temperature(0.7)
                .build();
    }

    private static String buildSystemInstruction(IAgentTool[] tools) {
        StringBuilder toolList = new StringBuilder();
        for (IAgentTool tool : tools) {
            toolList.append("  - ")
                    .append(tool.getName())
                    .append(": ")
                    .append(tool.getDescription())
                    .append('\n');
        }

        return """
                You are a helpful AI assistant running on an edge device.

                You have access to these tools:
                """ + toolList + """

                Tool strategy:
                - Use tools only when you genuinely need evidence to answer — do not fabricate.
                - Be efficient: make the minimum tool calls needed. Explore with list_files before reading files.
                - For large files, start with a small max_chars; read more only if needed.
                - If a tool returns an error, report what you tried and why it failed.
                - You may chain tools across turns to gather enough evidence.

                Output:
                - Answer in clear, well-structured Markdown.
                - Be concise. Explain your reasoning only when it adds value.
                - If you cannot find the information needed, say so honestly.
                """;
    }
}
