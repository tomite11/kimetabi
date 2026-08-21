package app.tabikime.kimetabi.settlement;

public class SettlementTransferVersionConflictException extends RuntimeException {

    private final SettlementTransferResource current;

    SettlementTransferVersionConflictException(SettlementTransferResource current) {
        super("Settlement transfer version conflict");
        this.current = current;
    }

    public SettlementTransferResource current() {
        return current;
    }
}
