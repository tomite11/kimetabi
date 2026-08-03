package app.tabikime.kimetabi.ingestion.url;

import java.io.IOException;

final class InvalidHttpResponseException extends IOException {

    InvalidHttpResponseException(String message) {
        super(message);
    }

    InvalidHttpResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
