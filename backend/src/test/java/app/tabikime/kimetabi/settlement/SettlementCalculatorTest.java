package app.tabikime.kimetabi.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class SettlementCalculatorTest {

    @Test
    void calculatesBalancesAndGreedyTransfersFromConfirmedExpenseSnapshots() {
        SettlementCalculation result = SettlementCalculator.calculate(List.of(
                expense(10, 0, 1, 900, share(1, 300), share(2, 300), share(3, 300)),
                expense(11, 2, 2, 600, share(1, 200), share(2, 200), share(3, 200))));

        assertThat(result.balances()).containsExactly(
                new MemberBalance(1, 400),
                new MemberBalance(2, 100),
                new MemberBalance(3, -500));
        assertThat(result.transfers()).containsExactly(
                new SettlementTransferDraft(3, 1, 400),
                new SettlementTransferDraft(3, 2, 100));
    }

    @Test
    void handlesOneMemberAndAllZeroBalancesWithoutTransfers() {
        SettlementCalculation oneMember = SettlementCalculator.calculate(List.of(
                expense(1, 0, 7, 120, share(7, 120))));
        SettlementCalculation equalPayments = SettlementCalculator.calculate(List.of(
                expense(2, 0, 1, 100, share(1, 100)),
                expense(3, 0, 2, 100, share(2, 100))));

        assertThat(oneMember.balances()).containsExactly(new MemberBalance(7, 0));
        assertThat(oneMember.transfers()).isEmpty();
        assertThat(equalPayments.transfers()).isEmpty();
    }

    @Test
    void usesMemberIdToBreakEqualBalanceTiesDeterministically() {
        List<SettlementExpenseSnapshot> input = List.of(
                expense(1, 0, 1, 200, share(3, 100), share(4, 100)),
                expense(2, 0, 2, 200, share(3, 100), share(4, 100)));

        assertThat(SettlementCalculator.calculate(input).transfers()).containsExactly(
                new SettlementTransferDraft(3, 1, 200),
                new SettlementTransferDraft(4, 2, 200));
        assertThat(SettlementCalculator.calculate(input).transfers())
                .isEqualTo(SettlementCalculator.calculate(input).transfers());
    }

    @Test
    void randomizedFixturesPreserveMoneyAndTransferCountInvariants() {
        Random random = new Random(60401L);
        for (int iteration = 0; iteration < 500; iteration++) {
            int memberCount = 1 + random.nextInt(30);
            int expenseCount = 1 + random.nextInt(80);
            List<SettlementExpenseSnapshot> expenses = new ArrayList<>();
            for (int expenseIndex = 0; expenseIndex < expenseCount; expenseIndex++) {
                long amount = 1 + random.nextInt(100_000);
                long payer = 1 + random.nextInt(memberCount);
                long firstShare = amount / memberCount;
                long residual = amount % memberCount;
                List<SettlementShareSnapshot> shares = new ArrayList<>();
                for (long memberId = 1; memberId <= memberCount; memberId++) {
                    shares.add(share(memberId, firstShare + (memberId <= residual ? 1 : 0)));
                }
                expenses.add(expense(expenseIndex + 1, 0, payer, amount, shares));
            }

            SettlementCalculation result = SettlementCalculator.calculate(expenses);
            long balanceTotal = result.balances().stream().mapToLong(MemberBalance::amount).sum();
            long debtTotal = result.balances().stream()
                    .filter(balance -> balance.amount() < 0)
                    .mapToLong(balance -> -balance.amount())
                    .sum();
            long transferTotal = result.transfers().stream()
                    .mapToLong(SettlementTransferDraft::amount).sum();
            long nonZeroMembers = result.balances().stream()
                    .filter(balance -> balance.amount() != 0).count();

            assertThat(balanceTotal).isZero();
            assertThat(transferTotal).isEqualTo(debtTotal);
            assertThat(result.transfers().size())
                    .isLessThanOrEqualTo((int) Math.max(0, nonZeroMembers - 1));
        }
    }

    @Test
    void rejectsInvalidOrNonReproducibleSnapshots() {
        assertThatThrownBy(() -> SettlementCalculator.calculate(List.of(
                expense(1, 0, 1, 100, share(1, 99)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("equal");
        assertThatThrownBy(() -> SettlementCalculator.calculate(List.of(
                expense(1, 0, 1, 100, share(1, 50), share(1, 50)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> SettlementCalculator.calculate(List.of(
                expense(1, 0, 1, 100, share(1, 100)),
                expense(1, 1, 1, 100, share(1, 100)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void rejectsBalancesOutsideTheSupportedBigintYenRange() {
        assertThatThrownBy(() -> SettlementCalculator.calculate(List.of(
                expense(1, 0, 1, Long.MAX_VALUE, share(2, Long.MAX_VALUE)),
                expense(2, 0, 1, 1, share(2, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range");
    }

    @Test
    void paidTransfersReduceOnlyTheRemainingDebt() {
        SettlementExpenseSnapshot expense = expense(
                1, 0, 1, 900, share(1, 300), share(2, 300), share(3, 300));
        SettlementCalculation result = SettlementCalculator.calculate(
                List.of(expense),
                List.of(new SettlementSourceTransferSnapshot(
                        50, 1, 2, 1, 200, TransferStatus.PAID)));

        assertThat(result.balances()).containsExactly(
                new MemberBalance(1, 400),
                new MemberBalance(2, -100),
                new MemberBalance(3, -300));
        assertThat(result.transfers()).containsExactly(
                new SettlementTransferDraft(3, 1, 300),
                new SettlementTransferDraft(2, 1, 100));
    }

    private static SettlementExpenseSnapshot expense(
            long id,
            long version,
            long payerId,
            long amount,
            SettlementShareSnapshot... shares
    ) {
        return expense(id, version, payerId, amount, List.of(shares));
    }

    private static SettlementExpenseSnapshot expense(
            long id,
            long version,
            long payerId,
            long amount,
            List<SettlementShareSnapshot> shares
    ) {
        return new SettlementExpenseSnapshot(id, version, payerId, amount, shares);
    }

    private static SettlementShareSnapshot share(long memberId, long amount) {
        return new SettlementShareSnapshot(memberId, amount);
    }
}
