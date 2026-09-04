package app.tabikime.kimetabi.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ExpenseAllocationReleaseRegressionTest {

    @Test
    void randomizedAllocationsAlwaysPreserveYenAndRemainDeterministic() {
        Random random = new Random(8101L);

        for (int iteration = 0; iteration < 1_000; iteration++) {
            long amount = 1 + random.nextLong(10_000_000L);
            int memberCount = 1 + random.nextInt(30);
            AllocationType type = AllocationType.values()[random.nextInt(AllocationType.values().length)];
            List<ExpenseShareInput> input = createInput(random, type, amount, memberCount);

            List<ExpenseShareResource> first = ExpenseAllocation.calculate(amount, type, input);
            List<ExpenseShareResource> second = ExpenseAllocation.calculate(amount, type, input);

            assertThat(first).isEqualTo(second);
            assertThat(first).extracting(ExpenseShareResource::memberId).isSorted();
            assertThat(first).extracting(ExpenseShareResource::memberId).doesNotHaveDuplicates();
            assertThat(first).allSatisfy(share -> assertThat(share.finalAmount()).isNotNegative());
            assertThat(first.stream().mapToLong(ExpenseShareResource::finalAmount).sum())
                    .isEqualTo(amount);
        }
    }

    private static List<ExpenseShareInput> createInput(
            Random random,
            AllocationType type,
            long amount,
            int memberCount
    ) {
        List<ExpenseShareInput> input = switch (type) {
            case EQUAL -> equalInput(memberCount);
            case WEIGHT -> weightedInput(random, memberCount);
            case FIXED_AND_WEIGHT -> fixedAndWeightInput(random, amount, memberCount);
        };
        Collections.shuffle(input, random);
        return List.copyOf(input);
    }

    private static List<ExpenseShareInput> equalInput(int memberCount) {
        List<ExpenseShareInput> input = new ArrayList<>();
        for (long memberId = 1; memberId <= memberCount; memberId++) {
            input.add(new ExpenseShareInput(memberId, null, null));
        }
        return input;
    }

    private static List<ExpenseShareInput> weightedInput(Random random, int memberCount) {
        List<ExpenseShareInput> input = new ArrayList<>();
        for (long memberId = 1; memberId <= memberCount; memberId++) {
            input.add(new ExpenseShareInput(
                    memberId,
                    BigDecimal.valueOf(1 + random.nextInt(10_000), 3),
                    null));
        }
        return input;
    }

    private static List<ExpenseShareInput> fixedAndWeightInput(
            Random random,
            long amount,
            int memberCount
    ) {
        if (memberCount == 1) {
            return new ArrayList<>(List.of(new ExpenseShareInput(1L, null, amount)));
        }

        int fixedCount = random.nextInt(memberCount);
        long fixedBudget = random.nextLong(amount + 1);
        long remainingFixedBudget = fixedBudget;
        List<ExpenseShareInput> input = new ArrayList<>();
        for (long memberId = 1; memberId <= fixedCount; memberId++) {
            long fixedAmount = memberId == fixedCount
                    ? remainingFixedBudget
                    : random.nextLong(remainingFixedBudget + 1);
            remainingFixedBudget -= fixedAmount;
            input.add(new ExpenseShareInput(memberId, null, fixedAmount));
        }
        for (long memberId = fixedCount + 1L; memberId <= memberCount; memberId++) {
            input.add(new ExpenseShareInput(
                    memberId,
                    BigDecimal.valueOf(1 + random.nextInt(10_000), 3),
                    null));
        }
        return input;
    }
}
