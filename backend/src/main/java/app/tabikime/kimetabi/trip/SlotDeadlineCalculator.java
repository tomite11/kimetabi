package app.tabikime.kimetabi.trip;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class SlotDeadlineCalculator {

    private final Clock clock;

    public SlotDeadlineCalculator(Clock clock) {
        this.clock = clock;
    }

    public Optional<LocalDate> calculate(
            SlotCategory category,
            LocalDate startsOn,
            String timezone
    ) {
        int daysBefore = switch (category) {
            case LODGING, TRANSPORT -> 14;
            case ACTIVITY -> 7;
            case MEAL -> 2;
            case OTHER -> -1;
        };
        if (daysBefore < 0) {
            return Optional.empty();
        }
        LocalDate calculated = startsOn.minusDays(daysBefore);
        LocalDate tomorrow = LocalDate.now(clock.withZone(ZoneId.of(timezone))).plusDays(1);
        return Optional.of(calculated.isBefore(tomorrow) ? tomorrow : calculated);
    }
}
