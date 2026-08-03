package app.tabikime.kimetabi.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.tabikime.kimetabi.trip.TripValidationException;

class ExpenseAllocationTest {

    @Test
    void equalAllocationUsesMemberIdAsDeterministicRemainderTieBreak() {
        List<ExpenseShareResource> result = ExpenseAllocation.calculate(
                100,
                AllocationType.EQUAL,
                List.of(
                        new ExpenseShareInput(30L, null, null),
                        new ExpenseShareInput(10L, null, null),
                        new ExpenseShareInput(20L, null, null)));

        assertThat(result).extracting(ExpenseShareResource::memberId)
                .containsExactly(10L, 20L, 30L);
        assertThat(result).extracting(ExpenseShareResource::finalAmount)
                .containsExactly(34L, 33L, 33L);
        assertThat(result).extracting(ExpenseShareResource::weight)
                .containsOnly(BigDecimal.ONE);
    }

    @Test
    void fixedAndWeightPersistsFixedBurdenAndAllocatesOnlyRemainder() {
        List<ExpenseShareResource> result = ExpenseAllocation.calculate(
                1000,
                AllocationType.FIXED_AND_WEIGHT,
                List.of(
                        new ExpenseShareInput(1L, null, 400L),
                        new ExpenseShareInput(2L, new BigDecimal("1"), null),
                        new ExpenseShareInput(3L, new BigDecimal("2"), null)));

        assertThat(result).extracting(ExpenseShareResource::finalAmount)
                .containsExactly(400L, 200L, 400L);
        assertThat(result.stream().mapToLong(ExpenseShareResource::finalAmount).sum())
                .isEqualTo(1000L);
    }

    @Test
    void rejectsDuplicateMembersAndFixedAmountAboveExpense() {
        assertThatThrownBy(() -> ExpenseAllocation.calculate(
                100,
                AllocationType.EQUAL,
                List.of(
                        new ExpenseShareInput(1L, null, null),
                        new ExpenseShareInput(1L, null, null))))
                .isInstanceOf(TripValidationException.class);

        assertThatThrownBy(() -> ExpenseAllocation.calculate(
                100,
                AllocationType.FIXED_AND_WEIGHT,
                List.of(new ExpenseShareInput(1L, null, 101L))))
                .isInstanceOf(TripValidationException.class);
    }
}
