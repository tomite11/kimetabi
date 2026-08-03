package app.tabikime.kimetabi.trip;

import java.util.List;

import app.tabikime.kimetabi.candidate.PlanItemResource;

public record TripSnapshot(
        TripResource trip,
        long currentMemberId,
        List<MemberResource> members,
        List<SlotResource> slots,
        List<PlanItemResource> planItems
) {
    public TripSnapshot {
        members = List.copyOf(members);
        slots = List.copyOf(slots);
        planItems = List.copyOf(planItems);
    }
}
