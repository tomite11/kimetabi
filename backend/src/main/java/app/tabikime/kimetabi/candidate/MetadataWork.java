package app.tabikime.kimetabi.candidate;

import java.util.UUID;

public record MetadataWork(
        UUID eventId,
        long candidateId,
        String url
) {
}
