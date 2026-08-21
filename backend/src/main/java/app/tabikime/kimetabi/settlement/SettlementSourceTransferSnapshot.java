package app.tabikime.kimetabi.settlement;

public record SettlementSourceTransferSnapshot(
        long transferId,
        long transferVersion,
        long fromMemberId,
        long toMemberId,
        long amount,
        TransferStatus status
) {
}
