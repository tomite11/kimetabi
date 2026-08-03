package app.tabikime.kimetabi.expense;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ExpenseShareInput(
        @NotNull @Min(1) Long memberId,
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax("1000000")
        @Digits(integer = 7, fraction = 6)
        BigDecimal weight,
        @Min(0) Long fixedAmount
) {
}
