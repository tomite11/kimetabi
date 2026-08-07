package app.tabikime.kimetabi.expense;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import app.tabikime.kimetabi.trip.InvalidCursorException;

final class ExpenseCursor {

    private ExpenseCursor() {
    }

    static String encode(Instant createdAt, long id) {
        String value = createdAt + "|" + id;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static Decoded decode(String cursor) {
        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8);
            int separator = value.lastIndexOf('|');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("Malformed cursor");
            }
            Instant createdAt = Instant.parse(value.substring(0, separator));
            long id = Long.parseLong(value.substring(separator + 1));
            if (id < 1) {
                throw new IllegalArgumentException("Malformed cursor");
            }
            return new Decoded(createdAt, id);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new InvalidCursorException();
        }
    }

    record Decoded(Instant createdAt, long id) {
    }
}
