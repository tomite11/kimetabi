package app.tabikime.kimetabi.trip;

public class TripNotFoundException extends RuntimeException {

    public TripNotFoundException() {
        super("Trip was not found");
    }
}
