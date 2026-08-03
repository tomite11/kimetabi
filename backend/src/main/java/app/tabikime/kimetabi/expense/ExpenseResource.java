package app.tabikime.kimetabi.expense;

import java.time.OffsetDateTime;
import java.util.List;

public record ExpenseResource(
        long id,
        Long planItemId,
        long createdByMemberId,
        Long payerId,
        Long amount,
        String currency,
        Long baseAmount,
        OffsetDateTime paidAt,
        ExpenseSource source,
        ExpenseStatus status,
        AllocationType allocationType,
        List<ExpenseReceiptResource> receipts,
        List<ExpenseShareResource> shares,
        long version
) {
}
