package app.tabikime.kimetabi.settlement;

import java.util.List;

public record SettlementExpenseSnapshot(
        long expenseId,
        long expenseVersion,
        long payerMemberId,
        long baseAmount,
        List<SettlementShareSnapshot> shares
) {
    public SettlementExpenseSnapshot {
        shares = shares == null ? List.of() : List.copyOf(shares);
    }
}
