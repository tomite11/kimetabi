package app.tabikime.kimetabi.trip;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TripRepository {

    private static final RowMapper<StoredTrip> TRIP_ROW_MAPPER = (resultSet, rowNumber) ->
            mapTrip(resultSet);
    private static final RowMapper<MemberResource> MEMBER_ROW_MAPPER = (resultSet, rowNumber) ->
            new MemberResource(
                    resultSet.getLong("id"),
                    resultSet.getString("name"),
                    MemberRole.valueOf(resultSet.getString("role")),
                    MemberStatus.valueOf(resultSet.getString("status")));

    private final JdbcClient jdbcClient;

    public TripRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    long insertTrip(CreateTripRequest request) {
        return jdbcClient.sql("""
                        INSERT INTO trip (
                            title, destination, starts_on, ends_on, timezone,
                            expected_member_count, vote_visibility, budget_cap
                        )
                        VALUES (
                            :title, :destination, :startsOn, :endsOn, :timezone,
                            :expectedMemberCount, :voteVisibility, :budgetCap
                        )
                        RETURNING id
                        """)
                .param("title", request.title().trim())
                .param("destination", request.destination().trim())
                .param("startsOn", request.startsOn())
                .param("endsOn", request.endsOn())
                .param("timezone", request.timezone().trim())
                .param("expectedMemberCount", request.expectedMemberCount())
                .param("voteVisibility", visibility(request).name())
                .param("budgetCap", request.budgetCap())
                .query(Long.class)
                .single();
    }

    long insertOwner(long tripId, String firebaseUid, String ownerName) {
        return jdbcClient.sql("""
                        INSERT INTO trip_member (
                            trip_id, firebase_uid, name, role, status
                        )
                        VALUES (:tripId, :firebaseUid, :name, 'OWNER', 'ACTIVE')
                        RETURNING id
                        """)
                .param("tripId", tripId)
                .param("firebaseUid", firebaseUid)
                .param("name", ownerName.trim())
                .query(Long.class)
                .single();
    }

    void setOwner(long tripId, long ownerMemberId) {
        int updated = jdbcClient.sql("""
                        UPDATE trip
                        SET owner_member_id = :ownerMemberId,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :tripId
                        """)
                .param("ownerMemberId", ownerMemberId)
                .param("tripId", tripId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("New trip disappeared before owner assignment");
        }
    }

    Optional<StoredTrip> findActiveMemberTrip(long tripId, String firebaseUid) {
        return jdbcClient.sql("""
                        SELECT t.*
                        FROM trip t
                        JOIN trip_member tm ON tm.trip_id = t.id
                        WHERE t.id = :tripId
                          AND t.deleted_at IS NULL
                          AND tm.firebase_uid = :firebaseUid
                          AND tm.status = 'ACTIVE'
                        """)
                .param("tripId", tripId)
                .param("firebaseUid", firebaseUid)
                .query(TRIP_ROW_MAPPER)
                .optional();
    }

    List<StoredTrip> listActiveMemberTrips(
            String firebaseUid,
            Instant cursorUpdatedAt,
            Long cursorId,
            int fetchLimit
    ) {
        if (cursorUpdatedAt == null) {
            return jdbcClient.sql("""
                            SELECT t.*
                            FROM trip t
                            JOIN trip_member tm ON tm.trip_id = t.id
                            WHERE t.deleted_at IS NULL
                              AND tm.firebase_uid = :firebaseUid
                              AND tm.status = 'ACTIVE'
                            ORDER BY t.updated_at DESC, t.id DESC
                            LIMIT :fetchLimit
                            """)
                    .param("firebaseUid", firebaseUid)
                    .param("fetchLimit", fetchLimit)
                    .query(TRIP_ROW_MAPPER)
                    .list();
        }
        return jdbcClient.sql("""
                        SELECT t.*
                        FROM trip t
                        JOIN trip_member tm ON tm.trip_id = t.id
                        WHERE t.deleted_at IS NULL
                          AND tm.firebase_uid = :firebaseUid
                          AND tm.status = 'ACTIVE'
                          AND (t.updated_at, t.id) < (:cursorUpdatedAt, :cursorId)
                        ORDER BY t.updated_at DESC, t.id DESC
                        LIMIT :fetchLimit
                        """)
                .param("firebaseUid", firebaseUid)
                .param("cursorUpdatedAt", OffsetDateTime.ofInstant(cursorUpdatedAt, java.time.ZoneOffset.UTC))
                .param("cursorId", cursorId)
                .param("fetchLimit", fetchLimit)
                .query(TRIP_ROW_MAPPER)
                .list();
    }

    List<MemberResource> listMembers(long tripId) {
        return jdbcClient.sql("""
                        SELECT id, name, role, status
                        FROM trip_member
                        WHERE trip_id = :tripId
                        ORDER BY joined_at, id
                        """)
                .param("tripId", tripId)
                .query(MEMBER_ROW_MAPPER)
                .list();
    }

    boolean claimIdempotencyKey(
            String firebaseUid,
            UUID idempotencyKey,
            String requestHash
    ) {
        return jdbcClient.sql("""
                        INSERT INTO idempotency_request (
                            firebase_uid, operation, idempotency_key,
                            request_hash, expires_at
                        )
                        VALUES (
                            :firebaseUid, 'CREATE_TRIP', :idempotencyKey,
                            :requestHash, CURRENT_TIMESTAMP + INTERVAL '24 hours'
                        )
                        ON CONFLICT (firebase_uid, operation, idempotency_key)
                        DO NOTHING
                        """)
                .param("firebaseUid", firebaseUid)
                .param("idempotencyKey", idempotencyKey)
                .param("requestHash", requestHash)
                .update() == 1;
    }

    IdempotencyRecord getIdempotencyRecord(String firebaseUid, UUID idempotencyKey) {
        return jdbcClient.sql("""
                        SELECT request_hash, response_body::text, resource_id
                        FROM idempotency_request
                        WHERE firebase_uid = :firebaseUid
                          AND operation = 'CREATE_TRIP'
                          AND idempotency_key = :idempotencyKey
                        """)
                .param("firebaseUid", firebaseUid)
                .param("idempotencyKey", idempotencyKey)
                .query((resultSet, rowNumber) -> new IdempotencyRecord(
                        resultSet.getString("request_hash"),
                        resultSet.getString("response_body"),
                        resultSet.getObject("resource_id", Long.class)))
                .single();
    }

    void completeIdempotencyKey(
            String firebaseUid,
            UUID idempotencyKey,
            long tripId,
            String responseBody
    ) {
        int updated = jdbcClient.sql("""
                        UPDATE idempotency_request
                        SET response_status = 201,
                            response_body = CAST(:responseBody AS jsonb),
                            resource_type = 'TRIP',
                            resource_id = :tripId
                        WHERE firebase_uid = :firebaseUid
                          AND operation = 'CREATE_TRIP'
                          AND idempotency_key = :idempotencyKey
                          AND resource_id IS NULL
                        """)
                .param("tripId", tripId)
                .param("responseBody", responseBody)
                .param("firebaseUid", firebaseUid)
                .param("idempotencyKey", idempotencyKey)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Idempotency record was not completed");
        }
    }

    private static StoredTrip mapTrip(ResultSet resultSet) throws SQLException {
        String phaseOverride = resultSet.getString("phase_override");
        return new StoredTrip(
                resultSet.getLong("id"),
                resultSet.getString("title"),
                resultSet.getString("destination"),
                resultSet.getObject("starts_on", LocalDate.class),
                resultSet.getObject("ends_on", LocalDate.class),
                resultSet.getString("timezone"),
                resultSet.getInt("expected_member_count"),
                phaseOverride == null ? null : TripPhase.valueOf(phaseOverride),
                VoteVisibility.valueOf(resultSet.getString("vote_visibility")),
                resultSet.getObject("budget_cap", Long.class),
                resultSet.getLong("revision"),
                resultSet.getLong("version"),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static VoteVisibility visibility(CreateTripRequest request) {
        return request.voteVisibility() == null ? VoteVisibility.NAMED : request.voteVisibility();
    }

    record StoredTrip(
            long id,
            String title,
            String destination,
            LocalDate startsOn,
            LocalDate endsOn,
            String timezone,
            int expectedMemberCount,
            TripPhase phaseOverride,
            VoteVisibility voteVisibility,
            Long budgetCap,
            long revision,
            long version,
            Instant updatedAt
    ) {
    }

    record IdempotencyRecord(String requestHash, String responseBody, Long resourceId) {
    }
}
