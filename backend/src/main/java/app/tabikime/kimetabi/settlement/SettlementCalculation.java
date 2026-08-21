package app.tabikime.kimetabi.settlement;

import java.util.List;

public record SettlementCalculation(
        List<MemberBalance> balances,
        List<SettlementTransferDraft> transfers
) {
    public SettlementCalculation {
        balances = List.copyOf(balances);
        transfers = List.copyOf(transfers);
    }
}
