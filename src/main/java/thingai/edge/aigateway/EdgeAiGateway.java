package thingai.edge.aigateway;

import de.kherud.llama.ModelParameters;
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

        inferenceHandler = new InferenceHandler(
                new ModelParameters()
                        .setHfRepo("ggml-org/gemma-4-E2B-it-GGUF:Q8_0")
                        .setVerbose()
        );

        inferenceHandler.testRun();
    }

    @Override
    protected void onServiceShutdown() {

    }
}
