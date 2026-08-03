package app.tabikime.kimetabi.candidate;

import java.util.List;

import app.tabikime.kimetabi.trip.VoteVisibility;

public record VoteView(
        VoteVisibility visibility,
        int yesCount,
        int anyCount,
        int noCount,
        List<Long> unvotedMemberIds,
        VoteResource myVote,
        List<VoteResource> namedVotes
) {
    public VoteView {
        unvotedMemberIds = List.copyOf(unvotedMemberIds);
        namedVotes = namedVotes == null ? null : List.copyOf(namedVotes);
    }
}
