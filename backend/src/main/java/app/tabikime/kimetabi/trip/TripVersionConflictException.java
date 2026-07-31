package app.tabikime.kimetabi.trip;

public class TripVersionConflictException extends RuntimeException {

    private final TripResource current;

    public TripVersionConflictException(TripResource current) {
        this.current = current;
    }

    public TripResource current() {
        return current;
    }
}
