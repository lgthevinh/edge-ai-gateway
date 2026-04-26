package thingai.edge.aigateway;

import org.thingai.base.Service;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.llm.LlmClient;
import thingai.edge.aigateway.llm.content.Content;
import thingai.edge.aigateway.llm.response.Response;
import thingai.edge.aigateway.llm.response.ResponseStreamCallback;

import java.util.concurrent.CompletableFuture;

public class EdgeAiGateway extends Service {
    private static final String TAG = "EdgeAiGateway";


    protected EdgeAiGateway() {
        super("edge-ai-gateway");
        setVersion("0.1.0");
        setAppDirName("edge-ai-gateway");
        ILog.ENABLE_LOGGING = true;
        ILog.logLevel = ILog.DEBUG;
    }

    @Override
    protected void onServiceInit() {
        ILog.d(TAG, "onServiceInit");

        LlmClient llmClient = new LlmClient("http://100.64.114.29:8080", "no-key", "model");
        Content content = new Content(null, "Hello, how are you?", 0.7);
        llmClient.chatCompletionAsync(content, new ResponseStreamCallback() {
            @Override
            public void onToken(String token) {
                ILog.d(TAG, "Received token: " + token);
            }

            @Override
            public void onComplete(String fullText) {
                ILog.d(TAG, "onComplete: " + fullText);
            }

            @Override
            public void onError(Exception e) {
                ILog.d(TAG, "onError: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onServiceShutdown() {

    }
}
