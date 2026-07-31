package app.tabikime.kimetabi.trip;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateTripRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 200) String destination,
        @NotNull LocalDate startsOn,
        @NotNull LocalDate endsOn,
        @NotBlank @Size(max = 40) String timezone,
        @Min(1) @Max(100) int expectedMemberCount,
        @NotBlank @Size(max = 100) String ownerName,
        @PositiveOrZero Long budgetCap,
        VoteVisibility voteVisibility
) {
}
