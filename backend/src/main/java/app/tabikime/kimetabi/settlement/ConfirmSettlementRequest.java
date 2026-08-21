package app.tabikime.kimetabi.settlement;

import jakarta.validation.constraints.PositiveOrZero;

public record ConfirmSettlementRequest(@PositiveOrZero long version) {
}
