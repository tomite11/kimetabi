package app.tabikime.kimetabi.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class TripPhasePolicyTest {

    private final TripPhasePolicy policy = new TripPhasePolicy(
            Clock.fixed(Instant.parse("2030-08-01T00:30:00Z"), ZoneOffset.UTC));

    @Test
    void usesTripTimezoneInsteadOfClockOrDeviceTimezone() {
        LocalDate travelDate = LocalDate.of(2030, 8, 1);

        assertThat(policy.determine(travelDate, travelDate, "Asia/Tokyo", null))
                .isEqualTo(TripPhase.TRAVELING);
        assertThat(policy.determine(travelDate, travelDate, "America/Los_Angeles", null))
                .isEqualTo(TripPhase.PLANNING);
    }

    @Test
    void treatsBothBoundaryDatesAsTravelingAndLaterDateAsSettling() {
        assertThat(policy.determine(
                LocalDate.of(2030, 7, 31),
                LocalDate.of(2030, 8, 1),
                "Asia/Tokyo",
                null)).isEqualTo(TripPhase.TRAVELING);
        assertThat(policy.determine(
                LocalDate.of(2030, 7, 30),
                LocalDate.of(2030, 7, 31),
                "Asia/Tokyo",
                null)).isEqualTo(TripPhase.SETTLING);
    }

    @Test
    void phaseOverrideAlwaysWins() {
        assertThat(policy.determine(
                LocalDate.of(2030, 8, 2),
                LocalDate.of(2030, 8, 3),
                "Asia/Tokyo",
                TripPhase.SETTLING)).isEqualTo(TripPhase.SETTLING);
    }
}
