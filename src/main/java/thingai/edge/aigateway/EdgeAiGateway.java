package thingai.edge.aigateway;

import org.thingai.base.Service;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.llm.LlmClient;
import thingai.edge.aigateway.llm.content.Content;
import thingai.edge.aigateway.llm.response.Response;

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

        LlmClient llmClient = new LlmClient("http://localhost:8080", "no-key", "model");
        Content content = new Content(null, "Hello, how are you?", 0.7);
        Response response = llmClient.chatCompletion(content);
        ILog.d(TAG, "Response: " + response.getMessageContent());
    }

    @Override
    protected void onServiceShutdown() {

    }
}
