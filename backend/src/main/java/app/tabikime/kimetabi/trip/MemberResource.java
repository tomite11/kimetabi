package app.tabikime.kimetabi.trip;

public record MemberResource(
        long id,
        String name,
        MemberRole role,
        MemberStatus status
) {
}
