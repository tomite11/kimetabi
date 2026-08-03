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

import app.tabikime.kimetabi.candidate.PlanItemResource;

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
    private static final RowMapper<SlotResource> SLOT_ROW_MAPPER = (resultSet, rowNumber) ->
            new SlotResource(
                    resultSet.getLong("id"),
                    SlotCategory.valueOf(resultSet.getString("category")),
                    resultSet.getString("title"),
                    resultSet.getInt("day_from"),
                    resultSet.getInt("day_to"),
                    resultSet.getInt("units"),
                    resultSet.getInt("sort_order"),
                    SlotStatus.valueOf(resultSet.getString("status")),
                    resultSet.getObject("deadline", LocalDate.class),
                    resultSet.getObject("est_per_person", Long.class),
                    resultSet.getObject("adopted_candidate_id", Long.class),
                    resultSet.getBoolean("auto_generated"),
                    resultSet.getLong("version"));

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

    void insertInitialSlots(long tripId, List<InitialSlotFactory.SlotDraft> slots) {
        for (InitialSlotFactory.SlotDraft slot : slots) {
            jdbcClient.sql("""
                            INSERT INTO slot (
                                trip_id, category, title, day_from, day_to, units,
                                sort_order, status, deadline, est_per_person, auto_generated
                            )
                            VALUES (
                                :tripId, :category, :title, :dayFrom, :dayTo, :units,
                                :sortOrder, 'OPEN', :deadline, :estPerPerson, TRUE
                            )
                            """)
                    .param("tripId", tripId)
                    .param("category", slot.category().name())
                    .param("title", slot.title())
                    .param("dayFrom", slot.dayFrom())
                    .param("dayTo", slot.dayTo())
                    .param("units", slot.units())
                    .param("sortOrder", slot.sortOrder())
                    .param("deadline", slot.deadline())
                    .param("estPerPerson", slot.estPerPerson())
                    .update();
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

    Optional<StoredTrip> findTrip(long tripId) {
        return jdbcClient.sql("""
                        SELECT *
                        FROM trip
                        WHERE id = :tripId
                          AND deleted_at IS NULL
                        """)
                .param("tripId", tripId)
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

    List<SlotResource> listSlots(long tripId) {
        return jdbcClient.sql("""
                        SELECT id, category, title, day_from, day_to, units, sort_order,
                               status, deadline, est_per_person, adopted_candidate_id,
                               auto_generated, version
                        FROM slot
                        WHERE trip_id = :tripId
                        ORDER BY sort_order, id
                        """)
                .param("tripId", tripId)
                .query(SLOT_ROW_MAPPER)
                .list();
    }

    List<PlanItemResource> listPlanItems(long tripId) {
        return jdbcClient.sql("""
                        SELECT id, slot_id, from_candidate_id, title, starts_at,
                               timezone, place_ref, version
                        FROM plan_item
                        WHERE trip_id = :tripId
                        ORDER BY slot_id, id
                        """)
                .param("tripId", tripId)
                .query((resultSet, rowNumber) -> new PlanItemResource(
                        resultSet.getLong("id"),
                        resultSet.getLong("slot_id"),
                        resultSet.getLong("from_candidate_id"),
                        resultSet.getString("title"),
                        resultSet.getObject("starts_at", OffsetDateTime.class),
                        resultSet.getString("timezone"),
                        resultSet.getString("place_ref"),
                        resultSet.getLong("version")))
                .list();
    }

    Optional<MemberRole> findActiveMemberRole(long tripId, String firebaseUid) {
        return jdbcClient.sql("""
                        SELECT role
                        FROM trip_member
                        WHERE trip_id = :tripId
                          AND firebase_uid = :firebaseUid
                          AND status = 'ACTIVE'
                        """)
                .param("tripId", tripId)
                .param("firebaseUid", firebaseUid)
                .query(String.class)
                .optional()
                .map(MemberRole::valueOf);
    }

    Optional<StoredMember> findActiveMember(long tripId, String firebaseUid) {
        return jdbcClient.sql("""
                        SELECT id, role
                        FROM trip_member
                        WHERE trip_id = :tripId
                          AND firebase_uid = :firebaseUid
                          AND status = 'ACTIVE'
                        """)
                .param("tripId", tripId)
                .param("firebaseUid", firebaseUid)
                .query((resultSet, rowNumber) -> new StoredMember(
                        resultSet.getLong("id"),
                        MemberRole.valueOf(resultSet.getString("role"))))
                .optional();
    }

    Optional<StoredMember> lockActiveMemberRole(long tripId, String firebaseUid) {
        return jdbcClient.sql("""
                        SELECT id, role
                        FROM trip_member
                        WHERE trip_id = :tripId
                          AND firebase_uid = :firebaseUid
                          AND status = 'ACTIVE'
                        FOR SHARE
                        """)
                .param("tripId", tripId)
                .param("firebaseUid", firebaseUid)
                .query((resultSet, rowNumber) -> new StoredMember(
                        resultSet.getLong("id"),
                        MemberRole.valueOf(resultSet.getString("role"))))
                .optional();
    }

    Optional<StoredMember> findActiveMember(long tripId, long memberId) {
        return jdbcClient.sql("""
                        SELECT id, role
                        FROM trip_member
                        WHERE trip_id = :tripId
                          AND id = :memberId
                          AND status = 'ACTIVE'
                        """)
                .param("tripId", tripId)
                .param("memberId", memberId)
                .query((resultSet, rowNumber) -> new StoredMember(
                        resultSet.getLong("id"),
                        MemberRole.valueOf(resultSet.getString("role"))))
                .optional();
    }

    Optional<StoredMember> findMember(long tripId, long memberId) {
        return jdbcClient.sql("""
                        SELECT id, role
                        FROM trip_member
                        WHERE trip_id = :tripId
                          AND id = :memberId
                        """)
                .param("tripId", tripId)
                .param("memberId", memberId)
                .query((resultSet, rowNumber) -> new StoredMember(
                        resultSet.getLong("id"),
                        MemberRole.valueOf(resultSet.getString("role"))))
                .optional();
    }

    Optional<StoredMembership> findMembership(long tripId, String firebaseUid) {
        return jdbcClient.sql("""
                        SELECT id, role, status
                        FROM trip_member
                        WHERE trip_id = :tripId
                          AND firebase_uid = :firebaseUid
                        """)
                .param("tripId", tripId)
                .param("firebaseUid", firebaseUid)
                .query((resultSet, rowNumber) -> new StoredMembership(
                        resultSet.getLong("id"),
                        MemberRole.valueOf(resultSet.getString("role")),
                        MemberStatus.valueOf(resultSet.getString("status"))))
                .optional();
    }

    boolean insertGuestMember(long tripId, String firebaseUid, String name) {
        return jdbcClient.sql("""
                        INSERT INTO trip_member (
                            trip_id, firebase_uid, name, role, status
                        )
                        VALUES (:tripId, :firebaseUid, :name, 'MEMBER', 'ACTIVE')
                        ON CONFLICT (trip_id, firebase_uid) DO NOTHING
                        """)
                .param("tripId", tripId)
                .param("firebaseUid", firebaseUid)
                .param("name", name)
                .update() == 1;
    }

    void restoreMember(long tripId, long memberId, String name) {
        int updated = jdbcClient.sql("""
                        UPDATE trip_member
                        SET name = :name,
                            status = 'ACTIVE',
                            left_at = NULL,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE trip_id = :tripId
                          AND id = :memberId
                          AND status <> 'ACTIVE'
                        """)
                .param("name", name)
                .param("tripId", tripId)
                .param("memberId", memberId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Inactive member could not be restored");
        }
    }

    boolean replaceMemberUid(long tripId, long memberId, String firebaseUid) {
        return jdbcClient.sql("""
                        UPDATE trip_member target
                        SET firebase_uid = :firebaseUid,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE target.trip_id = :tripId
                          AND target.id = :memberId
                          AND NOT EXISTS (
                              SELECT 1
                              FROM trip_member other
                              WHERE other.trip_id = target.trip_id
                                AND other.firebase_uid = :firebaseUid
                                AND other.id <> target.id
                          )
                        """)
                .param("firebaseUid", firebaseUid)
                .param("tripId", tripId)
                .param("memberId", memberId)
                .update() == 1;
    }

    void lockUidAssignment(long tripId, String firebaseUid) {
        jdbcClient.sql("""
                        SELECT 1 AS acquired
                        FROM (
                            SELECT pg_advisory_xact_lock(
                                hashtextextended(:firebaseUid, :tripId)
                            )
                        ) uid_lock
                        """)
                .param("firebaseUid", firebaseUid)
                .param("tripId", tripId)
                .query(Long.class)
                .single();
    }

    void touchTrip(long tripId) {
        int updated = jdbcClient.sql("""
                        UPDATE trip
                        SET revision = revision + 1,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :tripId
                          AND deleted_at IS NULL
                        """)
                .param("tripId", tripId)
                .update();
        if (updated != 1) {
            throw new TripNotFoundException();
        }
    }

    MemberResource getMemberResource(long tripId, long memberId) {
        return jdbcClient.sql("""
                        SELECT id, name, role, status
                        FROM trip_member
                        WHERE trip_id = :tripId
                          AND id = :memberId
                        """)
                .param("tripId", tripId)
                .param("memberId", memberId)
                .query(MEMBER_ROW_MAPPER)
                .single();
    }

    boolean slotBelongsToTrip(long tripId, long slotId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM slot
                            WHERE trip_id = :tripId
                              AND id = :slotId
                        )
                        """)
                .param("tripId", tripId)
                .param("slotId", slotId)
                .query(Boolean.class)
                .single();
    }

    Optional<StoredMember> lockActiveMember(long tripId, long memberId) {
        return jdbcClient.sql("""
                        SELECT id, role
                        FROM trip_member
                        WHERE trip_id = :tripId
                          AND id = :memberId
                          AND status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("tripId", tripId)
                .param("memberId", memberId)
                .query((resultSet, rowNumber) -> new StoredMember(
                        resultSet.getLong("id"),
                        MemberRole.valueOf(resultSet.getString("role"))))
                .optional();
    }

    boolean hasUnsettledBalance(long tripId, long memberId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM trip_member_unsettled_balance
                            WHERE trip_id = :tripId
                              AND member_id = :memberId
                              AND balance_yen <> 0
                        )
                        """)
                .param("tripId", tripId)
                .param("memberId", memberId)
                .query(Boolean.class)
                .single();
    }

    boolean transferOwner(long tripId, long ownerId, long newOwnerId, long expectedVersion) {
        int tripUpdated = jdbcClient.sql("""
                        UPDATE trip
                        SET owner_member_id = :newOwnerId,
                            revision = revision + 1,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :tripId
                          AND owner_member_id = :ownerId
                          AND version = :expectedVersion
                          AND deleted_at IS NULL
                          AND EXISTS (
                              SELECT 1
                              FROM trip_member target
                              WHERE target.trip_id = trip.id
                                AND target.id = :newOwnerId
                                AND target.status = 'ACTIVE'
                                AND target.role <> 'OWNER'
                          )
                        """)
                .param("newOwnerId", newOwnerId)
                .param("tripId", tripId)
                .param("ownerId", ownerId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (tripUpdated != 1) {
            return false;
        }
        int previousOwnerUpdated = jdbcClient.sql("""
                        UPDATE trip_member
                        SET role = 'ORGANIZER',
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE trip_id = :tripId
                          AND id = :ownerId
                          AND status = 'ACTIVE'
                          AND role = 'OWNER'
                        """)
                .param("ownerId", ownerId)
                .param("tripId", tripId)
                .update();
        if (previousOwnerUpdated != 1) {
            throw new IllegalStateException("Active owner disappeared during ownership transfer");
        }
        int newOwnerUpdated = jdbcClient.sql("""
                        UPDATE trip_member
                        SET role = 'OWNER',
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE trip_id = :tripId
                          AND id = :newOwnerId
                          AND status = 'ACTIVE'
                          AND role <> 'OWNER'
                        """)
                .param("newOwnerId", newOwnerId)
                .param("tripId", tripId)
                .update();
        if (newOwnerUpdated != 1) {
            throw new IllegalStateException(
                    "Active ownership transfer target disappeared during ownership transfer");
        }
        return true;
    }

    boolean changeMemberStatus(
            long tripId,
            long memberId,
            MemberStatus status,
            long expectedVersion
    ) {
        int tripUpdated = jdbcClient.sql("""
                        UPDATE trip
                        SET revision = revision + 1,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :tripId
                          AND version = :expectedVersion
                          AND deleted_at IS NULL
                        """)
                .param("tripId", tripId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (tripUpdated != 1) {
            return false;
        }
        int memberUpdated = jdbcClient.sql("""
                        UPDATE trip_member
                        SET status = :status,
                            left_at = CURRENT_TIMESTAMP,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE trip_id = :tripId
                          AND id = :memberId
                          AND status = 'ACTIVE'
                        """)
                .param("status", status.name())
                .param("tripId", tripId)
                .param("memberId", memberId)
                .update();
        if (memberUpdated != 1) {
            throw new IllegalStateException("Active member disappeared during status update");
        }
        return true;
    }

    boolean updateTrip(
            long tripId,
            String firebaseUid,
            long expectedVersion,
            UpdateTripRequest request
    ) {
        return jdbcClient.sql("""
                        UPDATE trip
                        SET title = COALESCE(:title, title),
                            destination = COALESCE(:destination, destination),
                            starts_on = COALESCE(:startsOn, starts_on),
                            ends_on = COALESCE(:endsOn, ends_on),
                            timezone = COALESCE(:timezone, timezone),
                            expected_member_count =
                                COALESCE(:expectedMemberCount, expected_member_count),
                            phase_override = CASE
                                WHEN :phaseOverridePresent THEN :phaseOverride
                                ELSE phase_override
                            END,
                            vote_visibility = COALESCE(:voteVisibility, vote_visibility),
                            budget_cap = CASE
                                WHEN :budgetCapPresent THEN :budgetCap
                                ELSE budget_cap
                            END,
                            revision = revision + 1,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :tripId
                          AND deleted_at IS NULL
                          AND version = :expectedVersion
                          AND EXISTS (
                              SELECT 1
                              FROM trip_member tm
                              WHERE tm.trip_id = trip.id
                                AND tm.firebase_uid = :firebaseUid
                                AND tm.status = 'ACTIVE'
                                AND tm.role IN ('OWNER', 'ORGANIZER')
                          )
                        """)
                .param("title", trimmed(request.title()))
                .param("destination", trimmed(request.destination()))
                .param("startsOn", request.startsOn())
                .param("endsOn", request.endsOn())
                .param("timezone", trimmed(request.timezone()))
                .param("expectedMemberCount", request.expectedMemberCount())
                .param("phaseOverridePresent", request.phaseOverridePresent())
                .param("phaseOverride",
                        request.phaseOverride() == null ? null : request.phaseOverride().name())
                .param("voteVisibility",
                        request.voteVisibility() == null ? null : request.voteVisibility().name())
                .param("budgetCapPresent", request.budgetCapPresent())
                .param("budgetCap", request.budgetCap())
                .param("tripId", tripId)
                .param("expectedVersion", expectedVersion)
                .param("firebaseUid", firebaseUid)
                .update() == 1;
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

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
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

    record StoredMember(long id, MemberRole role) {
    }

    record StoredMembership(long id, MemberRole role, MemberStatus status) {
    }
}
