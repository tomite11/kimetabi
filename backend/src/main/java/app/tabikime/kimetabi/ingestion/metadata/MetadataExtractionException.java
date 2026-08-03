package app.tabikime.kimetabi.ingestion.metadata;

public final class MetadataExtractionException extends Exception {

    public enum Disposition {
        RETRYABLE,
        PERMANENT
    }

    private final Disposition disposition;
    private final String errorCode;

    public MetadataExtractionException(
            Disposition disposition,
            String errorCode,
            String message
    ) {
        super(message);
        this.disposition = disposition;
        this.errorCode = errorCode;
    }

    public MetadataExtractionException(
            Disposition disposition,
            String errorCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.disposition = disposition;
        this.errorCode = errorCode;
    }

    public Disposition disposition() {
        return disposition;
    }

    public String errorCode() {
        return errorCode;
    }
}
