package thingai.edge.aigateway.agent.knowledge;

import org.thingai.base.log.ILog;
import thingai.edge.aigateway.agent.SystemInstructionProvider;
import thingai.edge.aigateway.handler.knowledge.KnowledgeHandler;

public class KnowledgeSystemInstructionProvider implements SystemInstructionProvider {
    private static final String TAG = "KnowledgeSystemInstructionProvider";

    private final KnowledgeHandler knowledgeHandler;
    private final int maxChars;

    public KnowledgeSystemInstructionProvider(KnowledgeHandler knowledgeHandler, int maxChars) {
        if (knowledgeHandler == null) {
            throw new IllegalArgumentException("knowledgeHandler is required");
        }
        this.knowledgeHandler = knowledgeHandler;
        this.maxChars = Math.max(0, maxChars);
    }

    @Override
    public String build(String baseInstruction) {
        String context = knowledgeHandler.buildSystemInstructionContext(maxChars);
        if (context.isBlank()) {
            return baseInstruction;
        }
        ILog.d(TAG, "build", "knowledgeContextChars=" + context.length());
        return baseInstruction + "\n\n" + context;
    }
}
