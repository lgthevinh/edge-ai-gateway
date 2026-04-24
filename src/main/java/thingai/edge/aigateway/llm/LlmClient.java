package thingai.edge.aigateway.llm;

public class LlmClient {
    private static final String TAG = "LlmService";

    private String baseUrl;
    private String apiKey; // if using

    public LlmClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }
}
