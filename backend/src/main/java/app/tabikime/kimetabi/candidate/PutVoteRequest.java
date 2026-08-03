package app.tabikime.kimetabi.candidate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PutVoteRequest(
        @NotNull VoteChoice choice,
        @Size(max = 1000) String reason,
        @Min(0) Long version
) {
}
