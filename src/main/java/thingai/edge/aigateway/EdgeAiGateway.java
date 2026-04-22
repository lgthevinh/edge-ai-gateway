package thingai.edge.aigateway;

import org.thingai.base.Service;
import org.thingai.base.log.ILog;
import thingai.edge.aigateway.inference.InferenceHandler;

public class EdgeAiGateway extends Service {
    private static final String TAG = "EdgeAiGateway";

    private InferenceHandler inferenceHandler;

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
    }

    @Override
    protected void onServiceShutdown() {

    }
}
