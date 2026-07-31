package app.tabikime.kimetabi.trip;

import jakarta.validation.constraints.Min;

public record MemberMutationRequest(
        @Min(0) long version
) {
}

