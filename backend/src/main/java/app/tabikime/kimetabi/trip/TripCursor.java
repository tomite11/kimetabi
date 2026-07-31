package app.tabikime.kimetabi.trip;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

final class TripCursor {

    private TripCursor() {
    }

    static String encode(Instant updatedAt, long id) {
        String value = updatedAt + "|" + id;
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
            Instant updatedAt = Instant.parse(value.substring(0, separator));
            long id = Long.parseLong(value.substring(separator + 1));
            if (id < 1) {
                throw new IllegalArgumentException("Malformed cursor");
            }
            return new Decoded(updatedAt, id);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new InvalidCursorException();
        }
    }

    record Decoded(Instant updatedAt, long id) {
    }
}
