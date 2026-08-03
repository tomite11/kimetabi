package app.tabikime.kimetabi.expense;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.tabikime.kimetabi.trip.TripValidationException;

final class ExpenseAllocation {

    private ExpenseAllocation() {
    }

    static List<ExpenseShareResource> calculate(
            long amount,
            AllocationType type,
            List<ExpenseShareInput> inputs
    ) {
        if (inputs == null || inputs.isEmpty()) {
            throw invalid("shares", "確定する支出には負担者が1人以上必要です。");
        }
        Set<Long> memberIds = new HashSet<>();
        if (inputs.stream().anyMatch(input -> !memberIds.add(input.memberId()))) {
            throw invalid("shares", "同じ負担者を複数回指定できません。");
        }
        return switch (type) {
            case EQUAL -> weighted(amount, inputs.stream()
                    .map(input -> normalizedWeight(input, BigDecimal.ONE, false)).toList());
            case WEIGHT -> weighted(amount, inputs.stream()
                    .map(input -> normalizedWeight(input, input.weight(), true)).toList());
            case FIXED_AND_WEIGHT -> fixedAndWeight(amount, inputs);
        };
    }

    private static List<ExpenseShareResource> fixedAndWeight(
            long amount,
            List<ExpenseShareInput> inputs
    ) {
        List<ExpenseShareResource> fixed = new ArrayList<>();
        List<ExpenseShareResource> weighted = new ArrayList<>();
        long fixedTotal = 0;
        for (ExpenseShareInput input : inputs) {
            boolean hasFixed = input.fixedAmount() != null;
            boolean hasWeight = input.weight() != null;
            if (hasFixed == hasWeight) {
                throw invalid("shares", "固定額またはweightのどちらか一方を指定してください。");
            }
            if (hasFixed) {
                try {
                    fixedTotal = Math.addExact(fixedTotal, input.fixedAmount());
                } catch (ArithmeticException exception) {
                    throw invalid("shares", "固定額の合計が大きすぎます。");
                }
                fixed.add(new ExpenseShareResource(
                        input.memberId(), null, input.fixedAmount(), input.fixedAmount()));
            } else {
                weighted.add(normalizedWeight(input, input.weight(), true));
            }
        }
        if (fixedTotal > amount) {
            throw invalid("shares", "固定額の合計は支出額以下にしてください。");
        }
        long remainder = amount - fixedTotal;
        if (remainder > 0 && weighted.isEmpty()) {
            throw invalid("shares", "残額を配分する負担者が必要です。");
        }
        List<ExpenseShareResource> result = new ArrayList<>(fixed);
        if (!weighted.isEmpty()) result.addAll(weighted(remainder, weighted));
        result.sort(Comparator.comparingLong(ExpenseShareResource::memberId));
        return List.copyOf(result);
    }

    private static ExpenseShareResource normalizedWeight(
            ExpenseShareInput input,
            BigDecimal weight,
            boolean required
    ) {
        if (input.fixedAmount() != null) {
            throw invalid("shares", "この按分方式では固定額を指定できません。");
        }
        if (required && (weight == null || weight.signum() <= 0)) {
            throw invalid("shares", "weightは正数で指定してください。");
        }
        return new ExpenseShareResource(input.memberId(), weight, null, null);
    }

    private static List<ExpenseShareResource> weighted(
            long amount,
            List<ExpenseShareResource> shares
    ) {
        BigDecimal totalWeight = shares.stream()
                .map(ExpenseShareResource::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.signum() <= 0) {
            throw invalid("shares", "weightの合計は正数にしてください。");
        }
        List<Remainder> allocations = new ArrayList<>();
        long allocated = 0;
        for (ExpenseShareResource share : shares) {
            BigDecimal exact = BigDecimal.valueOf(amount)
                    .multiply(share.weight())
                    .divide(totalWeight, 18, RoundingMode.DOWN);
            long floor = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            allocated = Math.addExact(allocated, floor);
            allocations.add(new Remainder(share, floor, exact.subtract(BigDecimal.valueOf(floor))));
        }
        long residual = amount - allocated;
        allocations.sort(Comparator.comparing(Remainder::fraction).reversed()
                .thenComparing(remainder -> remainder.share().memberId()));
        for (int index = 0; index < residual; index++) {
            Remainder current = allocations.get(index % allocations.size());
            allocations.set(index % allocations.size(), current.withAmount(current.amount() + 1));
        }
        return allocations.stream()
                .map(value -> new ExpenseShareResource(
                        value.share().memberId(), value.share().weight(), null, value.amount()))
                .sorted(Comparator.comparingLong(ExpenseShareResource::memberId))
                .toList();
    }

    private static TripValidationException invalid(String field, String message) {
        return new TripValidationException(field, message);
    }

    private record Remainder(ExpenseShareResource share, long amount, BigDecimal fraction) {
        Remainder withAmount(long value) {
            return new Remainder(share, value, fraction);
        }
    }
}
