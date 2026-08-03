package app.tabikime.kimetabi.candidate;

import java.util.List;
import java.util.Map;

import app.tabikime.kimetabi.trip.SlotResource;

public record SlotDetail(
        SlotResource slot,
        List<CandidateResource> candidates,
        Map<String, Object> votesByCandidate
) {
}
