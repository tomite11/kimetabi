package app.tabikime.kimetabi.expense;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.Min;

public record CreateExpenseDraftRequest(
        @Min(1) Long planItemId,
        @Min(1) Long amount,
        OffsetDateTime paidAt,
        ExpenseSource source,
        Boolean hasReceipt
) {
}
