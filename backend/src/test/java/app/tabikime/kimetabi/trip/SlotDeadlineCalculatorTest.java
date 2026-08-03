package app.tabikime.kimetabi.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class SlotDeadlineCalculatorTest {

    private final SlotDeadlineCalculator calculator = new SlotDeadlineCalculator(
            Clock.fixed(Instant.parse("2030-07-01T15:30:00Z"), ZoneOffset.UTC));

    @Test
    void calculatesCategorySpecificDeadlines() {
        LocalDate startsOn = LocalDate.of(2030, 8, 1);

        assertThat(calculator.calculate(SlotCategory.TRANSPORT, startsOn, "Asia/Tokyo"))
                .contains(LocalDate.of(2030, 7, 18));
        assertThat(calculator.calculate(SlotCategory.LODGING, startsOn, "Asia/Tokyo"))
                .contains(LocalDate.of(2030, 7, 18));
        assertThat(calculator.calculate(SlotCategory.ACTIVITY, startsOn, "Asia/Tokyo"))
                .contains(LocalDate.of(2030, 7, 25));
        assertThat(calculator.calculate(SlotCategory.MEAL, startsOn, "Asia/Tokyo"))
                .contains(LocalDate.of(2030, 7, 30));
        assertThat(calculator.calculate(SlotCategory.OTHER, startsOn, "Asia/Tokyo"))
                .isEmpty();
    }

    @Test
    void clampsToTomorrowInTheTripTimezone() {
        assertThat(calculator.calculate(
                SlotCategory.MEAL, LocalDate.of(2030, 7, 2), "Asia/Tokyo"))
                .contains(LocalDate.of(2030, 7, 3));
    }
}
