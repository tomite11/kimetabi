package app.tabikime.kimetabi.expense;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class UpdateExpenseRequest {

    @NotNull
    @Min(0)
    private Long version;
    @Min(1)
    private Long payerId;
    @Min(1)
    private Long amount;
    private OffsetDateTime paidAt;
    private AllocationType allocationType;
    @Size(min = 1, max = 100)
    private List<@Valid ExpenseShareInput> shares;
    private ExpenseStatus status;
    private boolean payerIdPresent;
    private boolean amountPresent;
    private boolean paidAtPresent;
    private boolean allocationTypePresent;
    private boolean sharesPresent;
    private boolean statusPresent;

    public Long version() { return version; }
    public Long payerId() { return payerId; }
    public Long amount() { return amount; }
    public OffsetDateTime paidAt() { return paidAt; }
    public AllocationType allocationType() { return allocationType; }
    public List<ExpenseShareInput> shares() { return shares; }
    public ExpenseStatus status() { return status; }
    public boolean payerIdPresent() { return payerIdPresent; }
    public boolean amountPresent() { return amountPresent; }
    public boolean paidAtPresent() { return paidAtPresent; }
    public boolean allocationTypePresent() { return allocationTypePresent; }
    public boolean sharesPresent() { return sharesPresent; }
    public boolean statusPresent() { return statusPresent; }

    public void setVersion(Long version) { this.version = version; }
    public void setPayerId(Long value) { this.payerId = value; this.payerIdPresent = true; }
    public void setAmount(Long value) { this.amount = value; this.amountPresent = true; }
    public void setPaidAt(OffsetDateTime value) { this.paidAt = value; this.paidAtPresent = true; }
    public void setAllocationType(AllocationType value) {
        this.allocationType = value;
        this.allocationTypePresent = true;
    }
    public void setShares(List<ExpenseShareInput> value) {
        this.shares = value;
        this.sharesPresent = true;
    }
    public void setStatus(ExpenseStatus value) { this.status = value; this.statusPresent = true; }
}
