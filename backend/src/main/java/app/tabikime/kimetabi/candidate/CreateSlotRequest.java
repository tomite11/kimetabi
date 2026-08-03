package app.tabikime.kimetabi.candidate;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import app.tabikime.kimetabi.trip.SlotCategory;

public record CreateSlotRequest(
        @NotNull SlotCategory category,
        @NotBlank @Size(max = 100) String title,
        @Min(1) int dayFrom,
        @Min(1) int dayTo,
        @Min(1) @Max(365) int units,
        @Min(0) int sortOrder,
        LocalDate deadline,
        @Min(0) Long estPerPerson
) {
}
