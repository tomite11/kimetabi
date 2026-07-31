package app.tabikime.kimetabi.ingestion.url;

public class UrlFetchException extends Exception {

    public enum Reason {
        INVALID_URL,
        BLOCKED_ADDRESS,
        DNS_FAILURE,
        TOO_MANY_REDIRECTS,
        RESPONSE_TOO_LARGE,
        TIMEOUT,
        TRANSPORT_FAILURE
    }

    private final Reason reason;

    public UrlFetchException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public UrlFetchException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
