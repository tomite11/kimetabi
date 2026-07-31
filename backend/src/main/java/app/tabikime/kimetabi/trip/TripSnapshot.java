package app.tabikime.kimetabi.trip;

import java.util.List;

public record TripSnapshot(
        TripResource trip,
        List<MemberResource> members,
        List<SlotResource> slots
) {
    public TripSnapshot {
        members = List.copyOf(members);
        slots = List.copyOf(slots);
    }
}
