package thingai.edge.aigateway.llm.response;

public interface ResponseStreamCallback {
    void onToken(String token);
    void onComplete(String fullText);
    void onError(Exception e);
    default void onUsage(ResponseUsage usage) {}
}
