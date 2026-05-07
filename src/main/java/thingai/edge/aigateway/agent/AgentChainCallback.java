package thingai.edge.aigateway.agent;

import thingai.edge.aigateway.llm.response.ResponseUsage;

public interface AgentChainCallback {
    /** Fired at the start of each orchestrator turn where tools were called. */
    void onTurn(int turn, String agentName, String[] toolsUsed);
    void onToken(String token);
    void onAgentComplete(int index, String agentName, String content, String display);
    void onComplete(String finalText);
    void onError(Exception e);
    default void onUsage(ResponseUsage usage) {}
}
