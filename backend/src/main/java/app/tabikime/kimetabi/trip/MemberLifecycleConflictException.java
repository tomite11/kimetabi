package app.tabikime.kimetabi.trip;

public class MemberLifecycleConflictException extends RuntimeException {

    private final TripSnapshot current;

    public MemberLifecycleConflictException(TripSnapshot current) {
        super("Member lifecycle update conflicted with a newer trip version");
        this.current = current;
    }

    public TripSnapshot current() {
        return current;
    }
}

