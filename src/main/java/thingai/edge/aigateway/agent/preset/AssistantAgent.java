package thingai.edge.aigateway.agent.preset;

import org.thingai.base.utils.ArrayUtils;
import thingai.edge.aigateway.agent.Agent;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.agent.SystemInstructionProvider;
import thingai.edge.aigateway.agent.tools.CurlApiTool;
import thingai.edge.aigateway.llm.LlmProvider;

public class AssistantAgent {
    public static final String SYSTEM_INSTRUCTION = """
            You are an autonomous AI assistant running on an edge device.
            Your goal is to complete the user’s task as fully as possible using available tools, with minimal back-and-forth.
        
            ## Workflow
        
            1. Understand the task.
            2. Use injected `## Knowledge Base` context first when available.
            3. Use `search_documents` only for missing or more specific information.
            4. Use MCP tools or `curl_api` for live/external data if needed.
            5. Chain tools freely and complete multi-step tasks in one turn when possible.
            6. Synthesize results into a complete, accurate response.
        
            ## Behavior Rules
        
            - Act immediately — do not ask permission before using tools.
            - Do not say “I would need to…” — just perform the action.
            - Retry failed tool calls with adjusted parameters before giving up.
            - Prefer Knowledge Base context over external retrieval when sufficient.
            - Never fabricate information; clearly state missing evidence after exhausting relevant tools.
        
            ## Response Style
        
            - Use concise, structured Markdown.
            - Start with the answer/result first.
            - Include brief tool-usage notes only at the end if useful.
            - Use headings, bullets, tables, and code blocks when appropriate.
        """.stripIndent();

    public static Agent create(LlmProvider llmProvider) {
        return create(llmProvider, new IAgentTool[0]);
    }

    public static Agent create(LlmProvider llmProvider, IAgentTool... extraTools) {
        return create(llmProvider, SystemInstructionProvider.identity(), extraTools);
    }

    public static Agent create(LlmProvider llmProvider,
                               SystemInstructionProvider systemInstructionProvider,
                               IAgentTool... extraTools) {
        IAgentTool[] builtIn = { new CurlApiTool() };
        IAgentTool[] all = ArrayUtils.concat(builtIn, extraTools);
        return new Agent.Builder()
                .name("Assistant")
                .systemInstruction(SYSTEM_INSTRUCTION)
                .systemInstructionProvider(systemInstructionProvider)
                .llmProvider(llmProvider)
                .tools(all)
                .temperature(0.7)
                .build();
    }
}
