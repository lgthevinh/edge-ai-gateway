package thingai.edge.aigateway.llm.message;

public interface MessageStreamCallback {
    void onToken(String token);
    void onComplete(String fullText);
    void onError(Exception e);
}
