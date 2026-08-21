package app.tabikime.kimetabi.settlement;

public record SettlementTransferResource(
        long id,
        long fromMemberId,
        long toMemberId,
        long amount,
        TransferStatus status,
        long version
) {
}
