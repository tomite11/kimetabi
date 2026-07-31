package app.tabikime.kimetabi.trip;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

@Component
public class TripPhasePolicy {

    private final Clock clock;

    public TripPhasePolicy(Clock clock) {
        this.clock = clock;
    }

    TripPhase determine(
            LocalDate startsOn,
            LocalDate endsOn,
            String timezone,
            TripPhase override
    ) {
        if (override != null) {
            return override;
        }
        LocalDate localDate = LocalDate.now(clock.withZone(ZoneId.of(timezone)));
        if (localDate.isBefore(startsOn)) {
            return TripPhase.PLANNING;
        }
        if (localDate.isAfter(endsOn)) {
            return TripPhase.SETTLING;
        }
        return TripPhase.TRAVELING;
    }
}
