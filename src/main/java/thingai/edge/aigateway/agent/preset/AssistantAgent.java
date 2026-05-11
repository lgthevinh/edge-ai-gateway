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
                    .append("  - ")
                    .append(tool.getParametersJson())
                    .append('\n');
        }

        return """
                You are a helpful AI assistant running on an edge device.
                
                You have access to these tools:
                """ + toolList + """
                
                Behavior:
                - Try to complete user tasks, never make up information, use tool to complete tasks if needed
                - Try NOT to ask user permission on list directory or read document — just do it when needed. Always use tools when you need them, don't hesitate or ask user for permission.
                - If you need to find any files or folders, use list directory tool to find directory.

                Tool strategy:
                - Use tools only when you genuinely need evidence to answer — do not fabricate.
                - Be consistent and completion: use tool as much as needed to gather sufficient evidence, but do not overuse.
                - For large files, start with a small max_chars; read more only if needed.
                - If a tool returns an error, report what you tried and why it failed.
                - You may chain tools across turns to gather enough evidence.

                Output:
                - Answer in clear, well-structured Markdown.
                - Be concise. Explain your reasoning only when it adds value.
                - If you cannot find the information needed, say so honestly.""";
    }
}
