package app.tabikime.kimetabi.trip;

import jakarta.validation.constraints.Min;

public record TransferOwnerRequest(
        @Min(1) long memberId,
        @Min(0) long version
) {
}

