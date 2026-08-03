package app.tabikime.kimetabi.candidate;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

public record ReorderSlotsRequest(
        @Min(0) long tripVersion,
        @NotEmpty List<@Valid Item> items
) {
    public ReorderSlotsRequest {
        items = items == null ? null : List.copyOf(items);
    }

    public record Item(
            @Min(1) long slotId,
            @Min(0) long version,
            @Min(0) int sortOrder
    ) {
    }
}
