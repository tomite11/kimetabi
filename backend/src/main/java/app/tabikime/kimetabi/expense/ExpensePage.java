package app.tabikime.kimetabi.expense;

import java.util.List;

public record ExpensePage(
        List<ExpenseResource> items,
        String nextCursor
) {
    public ExpensePage {
        items = List.copyOf(items);
    }
}
