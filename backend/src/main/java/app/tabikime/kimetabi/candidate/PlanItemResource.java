package app.tabikime.kimetabi.candidate;

import java.time.OffsetDateTime;

public record PlanItemResource(
        long id,
        long slotId,
        long fromCandidateId,
        String title,
        OffsetDateTime startsAt,
        String timezone,
        String placeRef,
        long version
) {
}
