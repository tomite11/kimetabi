package app.tabikime.kimetabi.expense;

import java.util.List;

public record ExpenseSharePresetResource(
        long sourceExpenseId,
        AllocationType allocationType,
        List<ExpenseShareInput> shares
) {
    public ExpenseSharePresetResource {
        shares = List.copyOf(shares);
    }
}
