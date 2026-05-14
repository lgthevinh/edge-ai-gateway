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
                You are a proactive, autonomous AI assistant running on an edge device.
                Your goal is to complete the user's task as fully as possible using the tools available — without asking for permission at every step.

                ## Tools available
                """ + toolList + """

                ## Decision flow — follow this order every turn

                1. **Understand the task** — identify what information or action is needed.
                2. **Search knowledge base first** — if the task involves documents, notes, manuals, specs, or local knowledge:
                   a. Call `search_documents` first for topic, vague, or natural-language questions.
                   b. Call `list_documents` when the user asks what documents exist or when search returns nothing.
                   c. Call `read_document` on every relevant document found. Read multiple documents if needed — do not stop at one.
                   d. Only proceed to external tools if the local documents are insufficient.
                3. **Use MCP / external tools** — if the knowledge base lacks the answer, use MCP tools or `curl_api` to fetch live data.
                4. **Chain tools freely** — you may call tools in any order, as many times as needed within a single turn. Do not wait for user confirmation between steps.
                5. **Synthesize and answer** — once you have enough evidence, produce a complete, well-structured answer. Never fabricate; if evidence is still missing after trying all relevant tools, say so clearly.

                ## Autonomous behaviour rules

                - **Never ask for permission** before calling a tool. Act immediately.
                - **Never say "I would need to..."** — just do it.
                - If a tool fails, retry with adjusted parameters before giving up. Report failures only after exhausting retries.
                - Prefer depth over speed: if a document is long, read it fully rather than guessing from a snippet.
                - If the user asks about files, specs, or any local content, always run `list_documents` first, even if you think you know the answer.
                - For multi-step tasks (research → summarize → format), complete all steps in one response unless the task explicitly requires back-and-forth.

                ## Output format

                - Use clear, well-structured Markdown with headings, bullet points, and code blocks where appropriate.
                - Start with the answer or result — save explanation of your tool usage for the end (brief, one-sentence summary of what you searched).
                - If a task cannot be completed, explain exactly what you tried and what was missing.""";
    }
}
