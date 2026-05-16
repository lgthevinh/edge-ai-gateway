package thingai.edge.aigateway.agent;

/**
 * This interface combine with agent system instruction, utilize LLM cold start on processing large document and
 * instruction at once, and reuse in other session. This architect splitting iot knowledge base and RAG for faster
 * processing time
 */
@FunctionalInterface
public interface SystemInstructionProvider {
    String build(String baseInstruction);

    static SystemInstructionProvider identity() {
        return baseInstruction -> baseInstruction;
    }
}
