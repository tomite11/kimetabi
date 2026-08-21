package app.tabikime.kimetabi.settlement;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateTransferRequest(
        @NotNull TransferStatus status,
        @PositiveOrZero long version
) {
}
