package thingai.edge.agent;

import org.thingai.base.Service;
import org.thingai.base.log.ILog;

public class EdgeAgentService extends Service {
    private static final String TAG = "EdgeAgentService";

    protected EdgeAgentService() {
        super("edge-agent");
        setVersion("0.1.0");
        setAppDirName("edge-agent");
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
