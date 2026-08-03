package app.tabikime.kimetabi.candidate;

public class VoteVersionConflictException extends RuntimeException {

    private final VoteResource current;

    public VoteVersionConflictException(VoteResource current) {
        this.current = current;
    }

    public VoteResource current() {
        return current;
    }
}
