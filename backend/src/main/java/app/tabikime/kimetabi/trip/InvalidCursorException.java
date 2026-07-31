package app.tabikime.kimetabi.trip;

public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException() {
        super("Cursor is malformed");
    }
}
