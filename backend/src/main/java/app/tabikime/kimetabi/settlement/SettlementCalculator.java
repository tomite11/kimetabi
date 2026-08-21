package app.tabikime.kimetabi.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SettlementCalculator {

    private static final BigDecimal MAX_YEN = BigDecimal.valueOf(Long.MAX_VALUE);

    private SettlementCalculator() {
    }

    public static SettlementCalculation calculate(List<SettlementExpenseSnapshot> expenses) {
        return calculate(expenses, List.of());
    }

    public static SettlementCalculation calculate(
            List<SettlementExpenseSnapshot> expenses,
            List<SettlementSourceTransferSnapshot> sourceTransfers
    ) {
        if (expenses == null) {
            throw new IllegalArgumentException("expenses must not be null");
        }
        if (sourceTransfers == null) {
            throw new IllegalArgumentException("sourceTransfers must not be null");
        }

        Map<Long, BigDecimal> balances = new HashMap<>();
        Set<Long> expenseIds = new HashSet<>();
        for (SettlementExpenseSnapshot expense : expenses) {
            validateExpense(expense, expenseIds);
            add(balances, expense.payerMemberId(), yen(expense.baseAmount(), "baseAmount"));

            BigDecimal burdenTotal = BigDecimal.ZERO;
            Set<Long> shareMemberIds = new HashSet<>();
            for (SettlementShareSnapshot share : expense.shares()) {
                if (share.memberId() <= 0 || !shareMemberIds.add(share.memberId())) {
                    throw new IllegalArgumentException("share member IDs must be positive and unique");
                }
                BigDecimal burden = nonNegativeYen(share.finalAmount(), "finalAmount");
                burdenTotal = burdenTotal.add(burden);
                add(balances, share.memberId(), burden.negate());
            }
            if (burdenTotal.compareTo(BigDecimal.valueOf(expense.baseAmount())) != 0) {
                throw new IllegalArgumentException("share burdens must equal the expense base amount");
            }
        }
        Set<Long> transferIds = new HashSet<>();
        for (SettlementSourceTransferSnapshot transfer : sourceTransfers) {
            validateSourceTransfer(transfer, transferIds);
            BigDecimal amount = yen(transfer.amount(), "source transfer amount");
            add(balances, transfer.fromMemberId(), amount);
            add(balances, transfer.toMemberId(), amount.negate());
        }

        List<MemberBalance> memberBalances = balances.entrySet().stream()
                .map(entry -> new MemberBalance(entry.getKey(), exactLong(entry.getValue())))
                .sorted(Comparator.comparingLong(MemberBalance::memberId))
                .toList();
        ensureBalanced(memberBalances);
        return new SettlementCalculation(memberBalances, createTransfers(memberBalances));
    }

    private static void validateSourceTransfer(
            SettlementSourceTransferSnapshot transfer,
            Set<Long> transferIds
    ) {
        if (transfer == null) {
            throw new IllegalArgumentException("sourceTransfers must not contain null");
        }
        if (transfer.transferId() <= 0 || !transferIds.add(transfer.transferId())) {
            throw new IllegalArgumentException("source transfer IDs must be positive and unique");
        }
        if (transfer.transferVersion() < 0
                || transfer.fromMemberId() <= 0
                || transfer.toMemberId() <= 0
                || transfer.fromMemberId() == transfer.toMemberId()) {
            throw new IllegalArgumentException("source transfer identity is invalid");
        }
        if (transfer.status() != TransferStatus.PAID
                && transfer.status() != TransferStatus.CONFIRMED) {
            throw new IllegalArgumentException("only paid source transfers can be recalculated");
        }
    }

    private static void validateExpense(
            SettlementExpenseSnapshot expense,
            Set<Long> expenseIds
    ) {
        if (expense == null) throw new IllegalArgumentException("expenses must not contain null");
        if (expense.expenseId() <= 0 || !expenseIds.add(expense.expenseId())) {
            throw new IllegalArgumentException("expense IDs must be positive and unique");
        }
        if (expense.expenseVersion() < 0) {
            throw new IllegalArgumentException("expenseVersion must not be negative");
        }
        if (expense.payerMemberId() <= 0) {
            throw new IllegalArgumentException("payerMemberId must be positive");
        }
        yen(expense.baseAmount(), "baseAmount");
        if (expense.shares().isEmpty()) {
            throw new IllegalArgumentException("a confirmed expense must have shares");
        }
    }

    private static List<SettlementTransferDraft> createTransfers(List<MemberBalance> balances) {
        Comparator<MemberBalance> descendingAmount = Comparator
                .comparingLong(MemberBalance::amount).reversed()
                .thenComparingLong(MemberBalance::memberId);
        Comparator<MemberBalance> ascendingAmount = Comparator
                .comparingLong(MemberBalance::amount)
                .thenComparingLong(MemberBalance::memberId);
        ArrayDeque<MutableBalance> creditors = balances.stream()
                .filter(balance -> balance.amount() > 0)
                .sorted(descendingAmount)
                .map(MutableBalance::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayDeque::new));
        ArrayDeque<MutableBalance> debtors = balances.stream()
                .filter(balance -> balance.amount() < 0)
                .sorted(ascendingAmount)
                .map(MutableBalance::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayDeque::new));

        List<SettlementTransferDraft> transfers = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            MutableBalance creditor = creditors.peek();
            MutableBalance debtor = debtors.peek();
            long amount = Math.min(creditor.amount, Math.negateExact(debtor.amount));
            transfers.add(new SettlementTransferDraft(debtor.memberId, creditor.memberId, amount));
            creditor.amount -= amount;
            debtor.amount += amount;
            if (creditor.amount == 0) creditors.remove();
            if (debtor.amount == 0) debtors.remove();
        }
        if (!creditors.isEmpty() || !debtors.isEmpty()) {
            throw new IllegalStateException("unbalanced settlement cannot produce transfers");
        }
        return List.copyOf(transfers);
    }

    private static void add(Map<Long, BigDecimal> balances, long memberId, BigDecimal amount) {
        BigDecimal updated = balances.getOrDefault(memberId, BigDecimal.ZERO).add(amount);
        if (updated.abs().compareTo(MAX_YEN) > 0) {
            throw new IllegalArgumentException("member balance exceeds the supported yen range");
        }
        balances.put(memberId, updated);
    }

    private static BigDecimal yen(long amount, String field) {
        if (amount <= 0) throw new IllegalArgumentException(field + " must be positive");
        return BigDecimal.valueOf(amount).setScale(0, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal nonNegativeYen(long amount, String field) {
        if (amount < 0) throw new IllegalArgumentException(field + " must not be negative");
        return BigDecimal.valueOf(amount).setScale(0, RoundingMode.UNNECESSARY);
    }

    private static long exactLong(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    private static void ensureBalanced(List<MemberBalance> balances) {
        BigDecimal total = balances.stream()
                .map(balance -> BigDecimal.valueOf(balance.amount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() != 0) {
            throw new IllegalStateException("member balances must sum to zero");
        }
    }

    private static final class MutableBalance {
        private final long memberId;
        private long amount;

        private MutableBalance(MemberBalance balance) {
            memberId = balance.memberId();
            amount = balance.amount();
        }
    }
}
