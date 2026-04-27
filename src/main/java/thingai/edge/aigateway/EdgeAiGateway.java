package thingai.edge.aigateway;

import org.thingai.base.Service;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import org.thingai.platform.dao.DaoSqlite;
import thingai.edge.aigateway.session.Session;
import thingai.edge.aigateway.session.SessionMessage;

public class EdgeAiGateway extends Service {
    private static final String TAG = "EdgeAiGateway";

    private static Dao dao;

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
        // init dao
        dao = new DaoSqlite(getAppDir() + "/data.db");
        dao.initDao(new Class[] {
                Session.class,
                SessionMessage.class
        });
    }

    @Override
    protected void onServiceShutdown() {

    }
}
