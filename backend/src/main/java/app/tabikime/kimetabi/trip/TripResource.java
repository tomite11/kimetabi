package app.tabikime.kimetabi.trip;

import java.time.LocalDate;

public record TripResource(
        long id,
        String title,
        String destination,
        LocalDate startsOn,
        LocalDate endsOn,
        String timezone,
        int expectedMemberCount,
        TripPhase phase,
        TripPhase phaseOverride,
        VoteVisibility voteVisibility,
        Long budgetCap,
        long revision,
        long version
) {
}
