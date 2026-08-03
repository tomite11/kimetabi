package app.tabikime.kimetabi.trip;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class InitialSlotFactory {

    static final long LODGING_ESTIMATE_PER_PERSON_PER_NIGHT = 10_000L;
    static final long TRANSPORT_ESTIMATE_PER_PERSON_PER_LEG = 12_000L;

    private final SlotDeadlineCalculator deadlineCalculator;

    public InitialSlotFactory(SlotDeadlineCalculator deadlineCalculator) {
        this.deadlineCalculator = deadlineCalculator;
    }

    List<SlotDraft> create(LocalDate startsOn, LocalDate endsOn, String timezone) {
        int nights = Math.toIntExact(ChronoUnit.DAYS.between(startsOn, endsOn));
        LocalDate transportDeadline = deadlineCalculator.calculate(
                SlotCategory.TRANSPORT, startsOn, timezone).orElseThrow();
        List<SlotDraft> slots = new ArrayList<>();
        if (nights == 0) {
            slots.add(new SlotDraft(
                    SlotCategory.TRANSPORT,
                    "往復の移動",
                    1,
                    1,
                    2,
                    0,
                    transportDeadline,
                    TRANSPORT_ESTIMATE_PER_PERSON_PER_LEG));
            return List.copyOf(slots);
        }

        slots.add(new SlotDraft(
                SlotCategory.TRANSPORT,
                "往路の移動",
                1,
                1,
                1,
                0,
                transportDeadline,
                TRANSPORT_ESTIMATE_PER_PERSON_PER_LEG));
        slots.add(new SlotDraft(
                SlotCategory.LODGING,
                "宿・" + nights + "泊",
                1,
                nights,
                nights,
                1,
                deadlineCalculator.calculate(SlotCategory.LODGING, startsOn, timezone)
                        .orElseThrow(),
                LODGING_ESTIMATE_PER_PERSON_PER_NIGHT));
        slots.add(new SlotDraft(
                SlotCategory.TRANSPORT,
                "復路の移動",
                nights + 1,
                nights + 1,
                1,
                2,
                transportDeadline,
                TRANSPORT_ESTIMATE_PER_PERSON_PER_LEG));
        return List.copyOf(slots);
    }

    record SlotDraft(
            SlotCategory category,
            String title,
            int dayFrom,
            int dayTo,
            int units,
            int sortOrder,
            LocalDate deadline,
            long estPerPerson
    ) {
    }
}
