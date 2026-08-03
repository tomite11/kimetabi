package app.tabikime.kimetabi.async;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MetadataTaskRequest(
        @NotNull UUID eventId,
        @Min(1) long candidateId
) {
}
