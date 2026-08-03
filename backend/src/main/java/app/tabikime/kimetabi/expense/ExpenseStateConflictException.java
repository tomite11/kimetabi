package app.tabikime.kimetabi.expense;

public class ExpenseStateConflictException extends RuntimeException {

    public ExpenseStateConflictException(String message) {
        super(message);
    }
}
