package app.tabikime.kimetabi.candidate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.tabikime.kimetabi.trip.SlotCategory;
import app.tabikime.kimetabi.trip.SlotResource;
import app.tabikime.kimetabi.trip.SlotStatus;

@Repository
class CandidateRepository {

    private final JdbcClient jdbcClient;

    CandidateRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    Optional<SlotResource> findSlot(long tripId, long slotId) {
        return jdbcClient.sql("""
                        SELECT id, category, title, day_from, day_to, units, sort_order,
                               status, deadline, est_per_person, adopted_candidate_id,
                               auto_generated, version
                        FROM slot
                        WHERE trip_id = :tripId AND id = :slotId
                        """)
                .param("tripId", tripId)
                .param("slotId", slotId)
                .query((resultSet, rowNumber) -> new SlotResource(
                        resultSet.getLong("id"),
                        SlotCategory.valueOf(resultSet.getString("category")),
                        resultSet.getString("title"),
                        resultSet.getInt("day_from"),
                        resultSet.getInt("day_to"),
                        resultSet.getInt("units"),
                        resultSet.getInt("sort_order"),
                        SlotStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("deadline", java.time.LocalDate.class),
                        resultSet.getObject("est_per_person", Long.class),
                        resultSet.getObject("adopted_candidate_id", Long.class),
                        resultSet.getBoolean("auto_generated"),
                        resultSet.getLong("version")))
                .optional();
    }

    int tripDayCount(long tripId) {
        return jdbcClient.sql("""
                        SELECT (ends_on - starts_on) + 1
                        FROM trip WHERE id = :tripId AND deleted_at IS NULL
                        """)
                .param("tripId", tripId)
                .query(Integer.class)
                .optional()
                .orElseThrow();
    }

    TripTiming tripTiming(long tripId) {
        return jdbcClient.sql("""
                        SELECT starts_on, timezone
                        FROM trip WHERE id = :tripId AND deleted_at IS NULL
                        """)
                .param("tripId", tripId)
                .query((resultSet, rowNumber) -> new TripTiming(
                        resultSet.getObject("starts_on", LocalDate.class),
                        resultSet.getString("timezone")))
                .single();
    }

    int slotCount(long tripId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM slot WHERE trip_id = :tripId")
                .param("tripId", tripId).query(Integer.class).single();
    }

    void lockTrip(long tripId) {
        jdbcClient.sql("SELECT id FROM trip WHERE id = :tripId FOR UPDATE")
                .param("tripId", tripId).query(Long.class).single();
    }

    boolean slotHasCandidates(long tripId, long slotId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM candidate
                            WHERE trip_id = :tripId AND slot_id = :slotId
                        )
                        """)
                .param("tripId", tripId).param("slotId", slotId)
                .query(Boolean.class).single();
    }

    long insertSlot(long tripId, CreateSlotRequest request) {
        jdbcClient.sql("""
                        UPDATE slot SET sort_order = sort_order + 1
                        WHERE trip_id = :tripId AND sort_order >= :sortOrder
                        """)
                .param("tripId", tripId)
                .param("sortOrder", request.sortOrder())
                .update();
        return jdbcClient.sql("""
                        INSERT INTO slot (
                            trip_id, category, title, day_from, day_to, units,
                            sort_order, status, deadline, est_per_person, auto_generated
                        ) VALUES (
                            :tripId, :category, :title, :dayFrom, :dayTo, :units,
                            :sortOrder, 'OPEN', :deadline, :estPerPerson, FALSE
                        ) RETURNING id
                        """)
                .param("tripId", tripId)
                .param("category", request.category().name())
                .param("title", request.title().trim())
                .param("dayFrom", request.dayFrom())
                .param("dayTo", request.dayTo())
                .param("units", request.units())
                .param("sortOrder", request.sortOrder())
                .param("deadline", request.deadline())
                .param("estPerPerson", request.estPerPerson())
                .query(Long.class).single();
    }

    boolean updateSlot(long tripId, long slotId, UpdateSlotRequest request) {
        SlotResource current = findSlot(tripId, slotId).orElseThrow();
        if (request.sortOrder() != null && request.sortOrder() != current.sortOrder()) {
            int temporaryOrder = -Math.toIntExact(slotId) - 1;
            jdbcClient.sql("""
                            UPDATE slot SET sort_order = :temporaryOrder
                            WHERE trip_id = :tripId AND id = :slotId AND version = :version
                            """)
                    .param("temporaryOrder", temporaryOrder)
                    .param("tripId", tripId)
                    .param("slotId", slotId)
                    .param("version", request.version())
                    .update();
            if (request.sortOrder() < current.sortOrder()) {
                jdbcClient.sql("""
                                UPDATE slot SET sort_order = sort_order + 1
                                WHERE trip_id = :tripId
                                  AND sort_order >= :newOrder AND sort_order < :oldOrder
                                """)
                        .param("tripId", tripId)
                        .param("newOrder", request.sortOrder())
                        .param("oldOrder", current.sortOrder()).update();
            } else {
                jdbcClient.sql("""
                                UPDATE slot SET sort_order = sort_order - 1
                                WHERE trip_id = :tripId
                                  AND sort_order > :oldOrder AND sort_order <= :newOrder
                                """)
                        .param("tripId", tripId)
                        .param("oldOrder", current.sortOrder())
                        .param("newOrder", request.sortOrder()).update();
            }
        }
        return jdbcClient.sql("""
                        UPDATE slot
                        SET title = CASE WHEN :titlePresent THEN :title ELSE title END,
                            day_from = CASE WHEN :dayFromPresent THEN :dayFrom ELSE day_from END,
                            day_to = CASE WHEN :dayToPresent THEN :dayTo ELSE day_to END,
                            units = CASE WHEN :unitsPresent THEN :units ELSE units END,
                            sort_order = CASE WHEN :sortOrderPresent THEN :sortOrder ELSE sort_order END,
                            deadline = CASE WHEN :deadlinePresent THEN :deadline ELSE deadline END,
                            est_per_person = CASE
                                WHEN :estPerPersonPresent THEN :estPerPerson ELSE est_per_person
                            END,
                            status = CASE WHEN :statusPresent THEN :status ELSE status END,
                            auto_generated = FALSE,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE trip_id = :tripId AND id = :slotId AND version = :version
                        """)
                .param("title", request.title() == null ? null : request.title().trim())
                .param("titlePresent", request.titlePresent())
                .param("dayFrom", request.dayFrom())
                .param("dayFromPresent", request.dayFromPresent())
                .param("dayTo", request.dayTo())
                .param("dayToPresent", request.dayToPresent())
                .param("units", request.units())
                .param("unitsPresent", request.unitsPresent())
                .param("sortOrder", request.sortOrder())
                .param("sortOrderPresent", request.sortOrderPresent())
                .param("deadline", request.deadline())
                .param("deadlinePresent", request.deadlinePresent())
                .param("estPerPerson", request.estPerPerson())
                .param("estPerPersonPresent", request.estPerPersonPresent())
                .param("status", enumName(request.status()))
                .param("statusPresent", request.statusPresent())
                .param("tripId", tripId)
                .param("slotId", slotId)
                .param("version", request.version())
                .update() == 1;
    }

    boolean deleteSlot(long tripId, long slotId, long version) {
        Optional<SlotResource> current = findSlot(tripId, slotId);
        if (current.isEmpty() || current.get().version() != version) {
            return false;
        }
        int oldOrder = current.get().sortOrder();
        int deleted = jdbcClient.sql("""
                        DELETE FROM slot
                        WHERE trip_id = :tripId AND id = :slotId AND version = :version
                          AND adopted_candidate_id IS NULL
                        """)
                .param("tripId", tripId)
                .param("slotId", slotId)
                .param("version", version).update();
        if (deleted == 1) {
            jdbcClient.sql("""
                            UPDATE slot SET sort_order = sort_order - 1
                            WHERE trip_id = :tripId AND sort_order > :oldOrder
                            """)
                    .param("tripId", tripId).param("oldOrder", oldOrder).update();
        }
        return deleted == 1;
    }

    long insert(long tripId, long slotId, long memberId, CreateCandidateRequest request) {
        return jdbcClient.sql("""
                        INSERT INTO candidate (
                            trip_id, slot_id, created_by_member_id, title, url, note,
                            est_amount, est_basis, metadata_status
                        ) VALUES (
                            :tripId, :slotId, :memberId, :title, :url, :note,
                            :estAmount, :estBasis, :metadataStatus
                        )
                        RETURNING id
                        """)
                .param("tripId", tripId)
                .param("slotId", slotId)
                .param("memberId", memberId)
                .param("title", request.title())
                .param("url", request.url())
                .param("note", request.note())
                .param("estAmount", request.estAmount())
                .param("estBasis", enumName(request.estBasis()))
                .param("metadataStatus", request.url() == null ? "COMPLETED" : "PENDING")
                .query(Long.class)
                .single();
    }

    void replaceTags(long tripId, long candidateId, List<String> tags) {
        jdbcClient.sql("DELETE FROM candidate_tag WHERE candidate_id = :candidateId")
                .param("candidateId", candidateId)
                .update();
        for (String tag : tags) {
            jdbcClient.sql("""
                            INSERT INTO candidate_tag (candidate_id, trip_id, tag)
                            VALUES (:candidateId, :tripId, :tag)
                            """)
                    .param("candidateId", candidateId)
                    .param("tripId", tripId)
                    .param("tag", tag)
                    .update();
        }
    }

    Optional<CandidateResource> find(long tripId, long candidateId) {
        return jdbcClient.sql("""
                        SELECT id, slot_id, created_by_member_id, title, url, image_url,
                               note, est_amount, est_basis, status, metadata_status,
                               metadata_error_code, version
                        FROM candidate
                        WHERE trip_id = :tripId AND id = :candidateId
                        """)
                .param("tripId", tripId)
                .param("candidateId", candidateId)
                .query((resultSet, rowNumber) -> mapCandidate(
                        resultSet, listTags(candidateId)))
                .optional();
    }

    List<CandidateResource> list(long tripId, long slotId) {
        return jdbcClient.sql("""
                        SELECT id, slot_id, created_by_member_id, title, url, image_url,
                               note, est_amount, est_basis, status, metadata_status,
                               metadata_error_code, version
                        FROM candidate
                        WHERE trip_id = :tripId AND slot_id = :slotId
                        ORDER BY created_at, id
                        """)
                .param("tripId", tripId)
                .param("slotId", slotId)
                .query((resultSet, rowNumber) -> mapCandidate(
                        resultSet, listTags(resultSet.getLong("id"))))
                .list();
    }

    boolean update(long tripId, long candidateId, UpdateCandidateRequest request) {
        return jdbcClient.sql("""
                        UPDATE candidate
                        SET title = CASE WHEN :titlePresent THEN :title ELSE title END,
                            url = CASE WHEN :urlPresent THEN :url ELSE url END,
                            note = CASE WHEN :notePresent THEN :note ELSE note END,
                            est_amount = CASE
                                WHEN :estAmountPresent THEN :estAmount ELSE est_amount
                            END,
                            est_basis = CASE WHEN :estBasisPresent THEN :estBasis ELSE est_basis END,
                            status = CASE WHEN :statusPresent THEN :status ELSE status END,
                            title_edited_at = CASE
                                WHEN NOT :titlePresent THEN title_edited_at
                                ELSE CURRENT_TIMESTAMP
                            END,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE trip_id = :tripId
                          AND id = :candidateId
                          AND version = :version
                        """)
                .param("title", request.title())
                .param("titlePresent", request.titlePresent())
                .param("url", request.url())
                .param("urlPresent", request.urlPresent())
                .param("note", request.note())
                .param("notePresent", request.notePresent())
                .param("estAmount", request.estAmount())
                .param("estAmountPresent", request.estAmountPresent())
                .param("estBasis", enumName(request.estBasis()))
                .param("estBasisPresent", request.estBasisPresent())
                .param("status", enumName(request.status()))
                .param("statusPresent", request.statusPresent())
                .param("tripId", tripId)
                .param("candidateId", candidateId)
                .param("version", request.version())
                .update() == 1;
    }

    private List<String> listTags(long candidateId) {
        return jdbcClient.sql("""
                        SELECT tag FROM candidate_tag
                        WHERE candidate_id = :candidateId
                        ORDER BY created_at, tag
                        """)
                .param("candidateId", candidateId)
                .query(String.class)
                .list();
    }

    private CandidateResource mapCandidate(
            java.sql.ResultSet resultSet,
            List<String> tags
    ) throws java.sql.SQLException {
        String basis = resultSet.getString("est_basis");
        return new CandidateResource(
                resultSet.getLong("id"),
                resultSet.getLong("slot_id"),
                resultSet.getLong("created_by_member_id"),
                resultSet.getString("title"),
                resultSet.getString("url"),
                resultSet.getString("image_url"),
                resultSet.getString("note"),
                tags,
                resultSet.getObject("est_amount", Long.class),
                basis == null ? null : EstimateBasis.valueOf(basis),
                CandidateStatus.valueOf(resultSet.getString("status")),
                MetadataStatus.valueOf(resultSet.getString("metadata_status")),
                resultSet.getString("metadata_error_code"),
                resultSet.getLong("version"));
    }

    record TripTiming(LocalDate startsOn, String timezone) {
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
