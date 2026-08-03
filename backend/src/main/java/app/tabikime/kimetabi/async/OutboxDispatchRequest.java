package app.tabikime.kimetabi.async;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record OutboxDispatchRequest(
        @Min(1) @Max(100) Integer limit
) {

    int resolvedLimit() {
        return limit == null ? 50 : limit;
    }
}
