package app.tabikime.kimetabi.candidate;

import jakarta.validation.constraints.Min;

public record AdoptCandidateRequest(
        @Min(1) long candidateId,
        @Min(0) long version
) {
}
