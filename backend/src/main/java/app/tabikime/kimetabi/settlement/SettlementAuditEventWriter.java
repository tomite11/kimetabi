package app.tabikime.kimetabi.settlement;

import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class SettlementAuditEventWriter {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    SettlementAuditEventWriter(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    void writeProxyTransferUpdate(long tripId, long actorMemberId,
            SettlementTransferResource before, SettlementTransferResource after) {
        jdbcClient.sql("""
                INSERT INTO audit_event (
                    trip_id, actor_member_id, action, resource_type,
                    resource_id, resource_version, before_state, after_state, trace_id
                ) VALUES (
                    :tripId, :actorMemberId, 'SETTLEMENT_TRANSFER_PROXY_UPDATED',
                    'settlementTransfer', :resourceId, :resourceVersion,
                    CAST(:beforeState AS jsonb), CAST(:afterState AS jsonb), :traceId
                )
                """)
                .param("tripId", tripId).param("actorMemberId", actorMemberId)
                .param("resourceId", after.id()).param("resourceVersion", after.version())
                .param("beforeState", json(before)).param("afterState", json(after))
                .param("traceId", traceId()).update();
    }

    private String json(SettlementTransferResource transfer) {
        try {
            return objectMapper.writeValueAsString(transfer);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize settlement audit state", exception);
        }
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? "unavailable" : traceId;
    }
}
