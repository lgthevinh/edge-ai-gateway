package thingai.edge.aigateway.session;

import org.thingai.base.dao.annotations.DaoColumn;
import org.thingai.base.dao.annotations.DaoTable;

@DaoTable(name = "sessions", version = 1)
public class Session {

    @DaoColumn(name = "session_id", primaryKey = true, nullable = false)
    public String sessionId;

    @DaoColumn(name = "agent_id", nullable = false)
    public String agentId;

    @DaoColumn(name = "created_at", nullable = false)
    public long createdAt;

    @DaoColumn(name = "updated_at", nullable = false)
    public long updatedAt;

    @DaoColumn(name = "temperature")
    public double temperature;

    @DaoColumn(name = "top_p")
    public double topP;

    @DaoColumn(name = "top_k")
    public int topK;

    public Session() {
    }

    public Session(String sessionId, String agentId, long createdAt, long updatedAt,
                   double temperature, double topP, int topK) {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.temperature = temperature;
        this.topP = topP;
        this.topK = topK;
    }
}
