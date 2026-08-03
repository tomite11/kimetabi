package app.tabikime.kimetabi.budget;

public record BudgetSimulation(
        long total,
        long perPerson,
        Long budgetCap,
        long estimatedTotal,
        long estimatedPerPerson
) {
}
