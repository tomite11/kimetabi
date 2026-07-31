package app.tabikime.kimetabi.trip;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccessTokenRepository {

    private final JdbcClient jdbcClient;

    public AccessTokenRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    long insertInvitation(long tripId, long creatorId, String tokenHash, Instant expiresAt) {
        return jdbcClient.sql("""
                        INSERT INTO invite_token (
                            trip_id, token_hash, created_by_member_id, expires_at
                        )
                        VALUES (:tripId, :tokenHash, :creatorId, :expiresAt)
                        RETURNING id
                        """)
                .param("tripId", tripId)
                .param("tokenHash", tokenHash)
                .param("creatorId", creatorId)
                .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    Optional<StoredInvitation> lockInvitation(String tokenHash) {
        return jdbcClient.sql("""
                        SELECT id, trip_id, expires_at, used_at, revoked_at
                        FROM invite_token
                        WHERE token_hash = :tokenHash
                        FOR UPDATE
                        """)
                .param("tokenHash", tokenHash)
                .query(AccessTokenRepository::mapInvitation)
                .optional();
    }

    boolean consumeInvitation(long invitationId) {
        return jdbcClient.sql("""
                        UPDATE invite_token
                        SET used_at = CURRENT_TIMESTAMP
                        WHERE id = :invitationId
                          AND used_at IS NULL
                          AND revoked_at IS NULL
                          AND expires_at > CURRENT_TIMESTAMP
                        """)
                .param("invitationId", invitationId)
                .update() == 1;
    }

    boolean revokeInvitation(long tripId, long invitationId) {
        return jdbcClient.sql("""
                        UPDATE invite_token
                        SET revoked_at = CURRENT_TIMESTAMP
                        WHERE id = :invitationId
                          AND trip_id = :tripId
                          AND used_at IS NULL
                          AND revoked_at IS NULL
                        """)
                .param("tripId", tripId)
                .param("invitationId", invitationId)
                .update() == 1;
    }

    boolean invitationBelongsToTrip(long tripId, long invitationId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM invite_token
                            WHERE id = :invitationId
                              AND trip_id = :tripId
                        )
                        """)
                .param("tripId", tripId)
                .param("invitationId", invitationId)
                .query(Boolean.class)
                .single();
    }

    long insertRecovery(
            long tripId,
            long memberId,
            long creatorId,
            String tokenHash,
            Instant expiresAt
    ) {
        return jdbcClient.sql("""
                        INSERT INTO recovery_token (
                            trip_id, member_id, token_hash,
                            created_by_member_id, expires_at
                        )
                        VALUES (
                            :tripId, :memberId, :tokenHash,
                            :creatorId, :expiresAt
                        )
                        RETURNING id
                        """)
                .param("tripId", tripId)
                .param("memberId", memberId)
                .param("tokenHash", tokenHash)
                .param("creatorId", creatorId)
                .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    Optional<StoredRecovery> lockRecovery(String tokenHash) {
        return jdbcClient.sql("""
                        SELECT id, trip_id, member_id, expires_at, used_at, revoked_at
                        FROM recovery_token
                        WHERE token_hash = :tokenHash
                        FOR UPDATE
                        """)
                .param("tokenHash", tokenHash)
                .query(AccessTokenRepository::mapRecovery)
                .optional();
    }

    boolean consumeRecovery(long recoveryId) {
        return jdbcClient.sql("""
                        UPDATE recovery_token
                        SET used_at = CURRENT_TIMESTAMP
                        WHERE id = :recoveryId
                          AND used_at IS NULL
                          AND revoked_at IS NULL
                          AND expires_at > CURRENT_TIMESTAMP
                        """)
                .param("recoveryId", recoveryId)
                .update() == 1;
    }

    private static StoredInvitation mapInvitation(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new StoredInvitation(
                resultSet.getLong("id"),
                resultSet.getLong("trip_id"),
                resultSet.getObject("expires_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("used_at", OffsetDateTime.class),
                resultSet.getObject("revoked_at", OffsetDateTime.class));
    }

    private static StoredRecovery mapRecovery(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new StoredRecovery(
                resultSet.getLong("id"),
                resultSet.getLong("trip_id"),
                resultSet.getLong("member_id"),
                resultSet.getObject("expires_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("used_at", OffsetDateTime.class),
                resultSet.getObject("revoked_at", OffsetDateTime.class));
    }

    record StoredInvitation(
            long id,
            long tripId,
            Instant expiresAt,
            OffsetDateTime usedAt,
            OffsetDateTime revokedAt
    ) {
    }

    record StoredRecovery(
            long id,
            long tripId,
            long memberId,
            Instant expiresAt,
            OffsetDateTime usedAt,
            OffsetDateTime revokedAt
    ) {
    }
}
