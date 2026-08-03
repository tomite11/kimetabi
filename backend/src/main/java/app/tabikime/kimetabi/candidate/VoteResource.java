package app.tabikime.kimetabi.candidate;

public record VoteResource(
        Long memberId,
        VoteChoice choice,
        String reason,
        long version
) {
}
