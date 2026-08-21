package app.tabikime.kimetabi.settlement;

import jakarta.validation.constraints.PositiveOrZero;

public record CreateSettlementRequest(@PositiveOrZero Long expectedTripRevision) {
}
