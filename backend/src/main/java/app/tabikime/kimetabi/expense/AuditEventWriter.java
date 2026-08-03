package app.tabikime.kimetabi.expense;

import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class AuditEventWriter {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    AuditEventWriter(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    void write(
            long tripId,
            long actorMemberId,
            String action,
            ExpenseResource before,
            ExpenseResource after
    ) {
        ExpenseResource resource = after != null ? after : before;
        jdbcClient.sql("""
                        INSERT INTO audit_event (
                            trip_id, actor_member_id, action, resource_type,
                            resource_id, resource_version, before_state,
                            after_state, trace_id
                        ) VALUES (
                            :tripId, :actorMemberId, :action, 'expense',
                            :resourceId, :resourceVersion, CAST(:beforeState AS jsonb),
                            CAST(:afterState AS jsonb), :traceId
                        )
                        """)
                .param("tripId", tripId)
                .param("actorMemberId", actorMemberId)
                .param("action", action)
                .param("resourceId", resource.id())
                .param("resourceVersion", resource.version())
                .param("beforeState", json(before))
                .param("afterState", json(after))
                .param("traceId", traceId())
                .update();
    }

    private String json(ExpenseResource resource) {
        if (resource == null) return null;
        try {
            return objectMapper.writeValueAsString(resource);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize audit state", exception);
        }
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? "unavailable" : traceId;
    }
}
