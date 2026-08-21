package app.tabikime.kimetabi.settlement;

import java.time.OffsetDateTime;
import java.util.List;

public record SettlementResource(
        long id,
        SettlementStatus status,
        OffsetDateTime calculatedAt,
        long expenseTotal,
        List<SettlementExpenseVersionResource> expenseVersions,
        List<SettlementTransferResource> transfers,
        boolean hasUnappliedChanges,
        long version
) {
}
