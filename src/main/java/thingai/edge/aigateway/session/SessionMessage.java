package thingai.edge.aigateway.session;

import org.thingai.base.dao.annotations.DaoColumn;
import org.thingai.base.dao.annotations.DaoTable;

@DaoTable(name = "session_messages", version = 1)
public class SessionMessage {

    @DaoColumn(name = "message_id", primaryKey = true, nullable = false)
    public String messageId;

    @DaoColumn(name = "session_id", nullable = false)
    public String sessionId;

    @DaoColumn(name = "sequence", nullable = false)
    public int sequence;

    @DaoColumn(name = "role", nullable = false)
    public String role;

    @DaoColumn(name = "content")
    public String content;

    @DaoColumn(name = "tool_calls_json")
    public String toolCallsJson;

    @DaoColumn(name = "tool_call_id")
    public String toolCallId;

    @DaoColumn(name = "created_at", nullable = false)
    public long createdAt;

    public SessionMessage() {
    }

    public SessionMessage(String messageId, String sessionId, int sequence, String role,
                          String content, String toolCallsJson, String toolCallId, long createdAt) {
        this.messageId = messageId;
        this.sessionId = sessionId;
        this.sequence = sequence;
        this.role = role;
        this.content = content;
        this.toolCallsJson = toolCallsJson;
        this.toolCallId = toolCallId;
        this.createdAt = createdAt;
    }
}
