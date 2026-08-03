package app.tabikime.kimetabi.candidate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RetryCandidateMetadataRequest(
        @NotNull @Min(0) Long version
) {
}
