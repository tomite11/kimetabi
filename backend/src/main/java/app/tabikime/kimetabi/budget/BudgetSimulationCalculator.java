package app.tabikime.kimetabi.budget;

import java.util.List;

import org.springframework.stereotype.Component;

import app.tabikime.kimetabi.candidate.EstimateBasis;

@Component
public class BudgetSimulationCalculator {

    public BudgetSimulation calculate(
            int expectedMemberCount,
            Long budgetCap,
            List<BudgetLine> lines
    ) {
        if (expectedMemberCount < 1) {
            throw new IllegalArgumentException("expectedMemberCount must be positive");
        }
        if (lines == null) {
            throw new IllegalArgumentException("lines are required");
        }

        long total = 0;
        long estimatedTotal = 0;
        for (BudgetLine line : List.copyOf(lines)) {
            long lineTotal = line.totalFor(expectedMemberCount);
            total = Math.addExact(total, lineTotal);
            if (line.estimated()) {
                estimatedTotal = Math.addExact(estimatedTotal, lineTotal);
            }
        }
        long perPerson = ceilingDivide(total, expectedMemberCount);
        return new BudgetSimulation(
                total,
                perPerson,
                budgetCap,
                estimatedTotal,
                ceilingDivide(estimatedTotal, expectedMemberCount));
    }

    private long ceilingDivide(long amount, int divisor) {
        return amount / divisor + (amount % divisor == 0 ? 0 : 1);
    }

    public record BudgetLine(
            long amount,
            EstimateBasis basis,
            int units,
            boolean estimated
    ) {
        public BudgetLine {
            if (amount < 0) {
                throw new IllegalArgumentException("amount must not be negative");
            }
            if (basis == null) {
                throw new IllegalArgumentException("basis is required");
            }
            if (units < 1) {
                throw new IllegalArgumentException("units must be positive");
            }
        }

        long totalFor(int expectedMemberCount) {
            if (basis == EstimateBasis.TOTAL) {
                return amount;
            }
            return Math.multiplyExact(
                    Math.multiplyExact(amount, units),
                    expectedMemberCount);
        }
    }
}
