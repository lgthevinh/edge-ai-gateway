package thingai.edge.aigateway.agent;

public interface AgentChainCallback {
    void onAgentComplete(int index, String agentName, String content, String display);
    void onComplete(String finalText);
    void onError(Exception e);
}
