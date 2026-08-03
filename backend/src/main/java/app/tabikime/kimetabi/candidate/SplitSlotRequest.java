package app.tabikime.kimetabi.candidate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record SplitSlotRequest(
        @Min(0) long version,
        @Min(1) int splitAfterDay,
        @Size(min = 1, max = 100) String secondTitle
) {
}
