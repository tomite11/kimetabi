package app.tabikime.kimetabi.expense;

import java.math.BigDecimal;

public record ExpenseShareResource(
        long memberId,
        BigDecimal weight,
        Long fixedAmount,
        Long finalAmount
) {
}
