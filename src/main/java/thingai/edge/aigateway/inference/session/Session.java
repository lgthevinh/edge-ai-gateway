package thingai.edge.aigateway.inference.session;

import org.thingai.base.dao.annotations.DaoColumn;
import org.thingai.base.dao.annotations.DaoTable;

@DaoTable(name = "sessions")
public class Session {
    @DaoColumn(primaryKey = true)
    private String sessionId;

    @DaoColumn()
    private String name;

    @DaoColumn()
    private int temperature;

    @DaoColumn()
    private int topK;

    @DaoColumn()
    private int topP;

    @DaoColumn()
    private boolean enableThinking;
}
