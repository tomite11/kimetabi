package app.tabikime.kimetabi.expense;

public class ExpenseVersionConflictException extends RuntimeException {

    private final ExpenseResource current;

    public ExpenseVersionConflictException(ExpenseResource current) {
        this.current = current;
    }

    public ExpenseResource current() {
        return current;
    }
}
