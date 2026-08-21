package app.tabikime.kimetabi.settlement;

public class SettlementVersionConflictException extends RuntimeException {

    private final SettlementResource current;

    public SettlementVersionConflictException(SettlementResource current) {
        super("Settlement version conflict");
        this.current = current;
    }

    public SettlementResource current() {
        return current;
    }
}
