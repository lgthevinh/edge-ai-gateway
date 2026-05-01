package thingai.edge.aigateway;

import org.thingai.base.Service;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import org.thingai.platform.dao.DaoSqlite;
import thingai.edge.aigateway.session.Session;
import thingai.edge.aigateway.session.SessionMessage;

public class EdgeAiGateway extends Service {
    private static final String TAG = "EdgeAiGateway";

    private Dao dao;
    private String llamaServerUrl;

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

    public Dao getDao() {
        return dao;
    }

    public void setLlamaServerUrl(String llamaServerUrl) {
        this.llamaServerUrl = llamaServerUrl;
    }

    public String getLlamaServerUrl() {
        return llamaServerUrl;
    }

    @Override
    protected void onServiceShutdown() {

    }
}
