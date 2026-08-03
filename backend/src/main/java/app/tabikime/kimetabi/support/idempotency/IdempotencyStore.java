package app.tabikime.kimetabi.support.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import app.tabikime.kimetabi.trip.IdempotencyConflictException;

@Component
public class IdempotencyStore {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public IdempotencyStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public String hash(Object request) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not hash idempotent request", exception);
        }
    }

    public Replay claimOrReplay(
            String firebaseUid,
            String operation,
            UUID idempotencyKey,
            String requestHash
    ) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO idempotency_request (
                            firebase_uid, operation, idempotency_key,
                            request_hash, expires_at
                        ) VALUES (
                            :firebaseUid, :operation, :idempotencyKey,
                            :requestHash, CURRENT_TIMESTAMP + INTERVAL '24 hours'
                        )
                        ON CONFLICT (firebase_uid, operation, idempotency_key) DO NOTHING
                        """)
                .param("firebaseUid", firebaseUid)
                .param("operation", operation)
                .param("idempotencyKey", idempotencyKey)
                .param("requestHash", requestHash)
                .update();
        if (inserted == 1) {
            return null;
        }
        Replay replay = jdbcClient.sql("""
                        SELECT request_hash, response_body::text, resource_id
                        FROM idempotency_request
                        WHERE firebase_uid = :firebaseUid
                          AND operation = :operation
                          AND idempotency_key = :idempotencyKey
                        """)
                .param("firebaseUid", firebaseUid)
                .param("operation", operation)
                .param("idempotencyKey", idempotencyKey)
                .query((resultSet, rowNumber) -> new Replay(
                        resultSet.getString("request_hash"),
                        resultSet.getString("response_body"),
                        resultSet.getLong("resource_id")))
                .single();
        if (!replay.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        if (replay.responseBody() == null) {
            throw new IllegalStateException("Incomplete idempotency record");
        }
        return replay;
    }

    public void complete(
            String firebaseUid,
            String operation,
            UUID idempotencyKey,
            String resourceType,
            long resourceId,
            Object response
    ) {
        String responseBody;
        try {
            responseBody = objectMapper.writeValueAsString(response);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not store idempotent response", exception);
        }
        int updated = jdbcClient.sql("""
                        UPDATE idempotency_request
                        SET response_status = 201,
                            response_body = CAST(:responseBody AS jsonb),
                            resource_type = :resourceType,
                            resource_id = :resourceId
                        WHERE firebase_uid = :firebaseUid
                          AND operation = :operation
                          AND idempotency_key = :idempotencyKey
                          AND resource_id IS NULL
                        """)
                .param("responseBody", responseBody)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("firebaseUid", firebaseUid)
                .param("operation", operation)
                .param("idempotencyKey", idempotencyKey)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Idempotency record was not completed");
        }
    }

    public <T> T read(Replay replay, Class<T> responseType) {
        try {
            return objectMapper.readValue(replay.responseBody(), responseType);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not read idempotent response", exception);
        }
    }

    public record Replay(String requestHash, String responseBody, long resourceId) {
    }
}
