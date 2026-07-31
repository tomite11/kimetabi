package app.tabikime.kimetabi.trip;

public class TripValidationException extends RuntimeException {

    private final String field;

    public TripValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
