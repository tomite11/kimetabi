package app.tabikime.kimetabi.internal;

public final class InternalOidcVerificationException extends Exception {

    public InternalOidcVerificationException(String message) {
        super(message);
    }

    public InternalOidcVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
