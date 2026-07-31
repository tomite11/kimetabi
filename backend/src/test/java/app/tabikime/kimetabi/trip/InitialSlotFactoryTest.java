package app.tabikime.kimetabi.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class InitialSlotFactoryTest {

    private final InitialSlotFactory factory = new InitialSlotFactory(
            Clock.fixed(Instant.parse("2030-07-01T00:30:00Z"), ZoneOffset.UTC));

    @Test
    void createsOnlyOutboundConsecutiveLodgingAndReturnForMultiDayTrip() {
        var slots = factory.create(
                LocalDate.of(2030, 8, 1),
                LocalDate.of(2030, 8, 3),
                "Asia/Tokyo");

        assertThat(slots).extracting(InitialSlotFactory.SlotDraft::title)
                .containsExactly("往路の移動", "宿・2泊", "復路の移動");
        assertThat(slots.get(1))
                .extracting(
                        InitialSlotFactory.SlotDraft::dayFrom,
                        InitialSlotFactory.SlotDraft::dayTo,
                        InitialSlotFactory.SlotDraft::units,
                        InitialSlotFactory.SlotDraft::estPerPerson)
                .containsExactly(1, 2, 2, 10_000L);
        assertThat(slots).allSatisfy(slot ->
                assertThat(slot.deadline()).isEqualTo(LocalDate.of(2030, 7, 18)));
    }

    @Test
    void createsSingleRoundTripSlotForDayTrip() {
        var slots = factory.create(
                LocalDate.of(2030, 8, 1),
                LocalDate.of(2030, 8, 1),
                "Asia/Tokyo");

        assertThat(slots).singleElement().satisfies(slot -> {
            assertThat(slot.title()).isEqualTo("往復の移動");
            assertThat(slot.units()).isEqualTo(2);
            assertThat(slot.category()).isEqualTo(SlotCategory.TRANSPORT);
        });
    }

    @Test
    void clampsDeadlineToTomorrowInTripTimezone() {
        var slots = factory.create(
                LocalDate.of(2030, 7, 2),
                LocalDate.of(2030, 7, 3),
                "Asia/Tokyo");

        assertThat(slots).allSatisfy(slot ->
                assertThat(slot.deadline()).isEqualTo(LocalDate.of(2030, 7, 2)));
    }
}
