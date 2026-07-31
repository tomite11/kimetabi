package app.tabikime.kimetabi.trip;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency key was reused with a different request");
    }
}
