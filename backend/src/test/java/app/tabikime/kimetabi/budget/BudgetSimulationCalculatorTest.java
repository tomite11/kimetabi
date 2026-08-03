package app.tabikime.kimetabi.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import app.tabikime.kimetabi.budget.BudgetSimulationCalculator.BudgetLine;
import app.tabikime.kimetabi.candidate.EstimateBasis;

class BudgetSimulationCalculatorTest {

    private final BudgetSimulationCalculator calculator = new BudgetSimulationCalculator();

    @Test
    void appliesMembersAndUnitsOnlyToPerPersonAmounts() {
        BudgetSimulation result = calculator.calculate(3, 100_000L, List.of(
                new BudgetLine(10_000, EstimateBasis.PER_PERSON, 2, false),
                new BudgetLine(20_000, EstimateBasis.TOTAL, 9, false)));

        assertThat(result).isEqualTo(new BudgetSimulation(
                80_000, 26_667, 100_000L, 0, 0));
    }

    @Test
    void usesExpectedMembersAndDoesNotAcceptAnActiveMemberCount() {
        BudgetLine lodging = new BudgetLine(
                10_000, EstimateBasis.PER_PERSON, 2, true);

        BudgetSimulation result = calculator.calculate(4, null, List.of(lodging));

        assertThat(result.total()).isEqualTo(80_000);
        assertThat(result.perPerson()).isEqualTo(20_000);
        assertThat(result.estimatedTotal()).isEqualTo(80_000);
        assertThat(result.estimatedPerPerson()).isEqualTo(20_000);
    }

    @Test
    void roundsPerPersonUpToAWholeYen() {
        BudgetSimulation result = calculator.calculate(3, null, List.of(
                new BudgetLine(10_000, EstimateBasis.TOTAL, 1, false)));

        assertThat(result.perPerson()).isEqualTo(3_334);
    }

    @Test
    void reportsOnlyPlaceholderLinesAsEstimated() {
        BudgetSimulation result = calculator.calculate(2, null, List.of(
                new BudgetLine(10_000, EstimateBasis.PER_PERSON, 2, true),
                new BudgetLine(18_000, EstimateBasis.PER_PERSON, 1, false)));

        assertThat(result.total()).isEqualTo(76_000);
        assertThat(result.estimatedTotal()).isEqualTo(40_000);
        assertThat(result.estimatedPerPerson()).isEqualTo(20_000);
    }

    @Test
    void rejectsInvalidDomainInputsAndArithmeticOverflow() {
        assertThatThrownBy(() -> calculator.calculate(0, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetLine(-1, EstimateBasis.TOTAL, 1, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(100, null, List.of(
                new BudgetLine(Long.MAX_VALUE, EstimateBasis.PER_PERSON, 1, false))))
                .isInstanceOf(ArithmeticException.class);
    }
}
