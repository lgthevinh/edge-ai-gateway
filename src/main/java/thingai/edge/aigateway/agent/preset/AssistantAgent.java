package thingai.edge.aigateway.agent.preset;

import thingai.edge.aigateway.agent.Agent;
import thingai.edge.aigateway.agent.tools.CurlApiTool;
import thingai.edge.aigateway.agent.tools.ListFilesTool;
import thingai.edge.aigateway.agent.tools.ReadFileTool;
import thingai.edge.aigateway.llm.LlmProvider;

public class AssistantAgent {

    private static final String SYSTEM_INSTRUCTION = """
            You are a helpful AI assistant running on an edge device.

            You have access to these tools:
              - list_files(path): list files and directories — use to explore before reading
              - read_file(path, max_chars?): read a file's contents (default 8000 chars; use max_chars to limit)
              - curl_api(url, method?, headers?, body?): make HTTP requests

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

    public static Agent create(LlmProvider llmProvider) {
        return new Agent.Builder()
                .name("Assistant")
                .systemInstruction(SYSTEM_INSTRUCTION)
                .llmProvider(llmProvider)
                .tools(new ListFilesTool(), new ReadFileTool(), new CurlApiTool())
                .temperature(0.7)
                .build();
    }
}
