package app.tabikime.kimetabi.candidate;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import app.tabikime.kimetabi.trip.SlotStatus;

public final class UpdateSlotRequest {

    @NotNull @Min(0) private Long version;
    @Size(min = 1, max = 100) private String title;
    @Min(1) private Integer dayFrom;
    @Min(1) private Integer dayTo;
    @Min(1) @Max(365) private Integer units;
    @Min(0) private Integer sortOrder;
    private LocalDate deadline;
    @Min(0) private Long estPerPerson;
    private SlotStatus status;
    private boolean titlePresent;
    private boolean dayFromPresent;
    private boolean dayToPresent;
    private boolean unitsPresent;
    private boolean sortOrderPresent;
    private boolean deadlinePresent;
    private boolean estPerPersonPresent;
    private boolean statusPresent;

    public Long version() { return version; }
    public String title() { return title; }
    public Integer dayFrom() { return dayFrom; }
    public Integer dayTo() { return dayTo; }
    public Integer units() { return units; }
    public Integer sortOrder() { return sortOrder; }
    public LocalDate deadline() { return deadline; }
    public Long estPerPerson() { return estPerPerson; }
    public SlotStatus status() { return status; }
    public boolean titlePresent() { return titlePresent; }
    public boolean dayFromPresent() { return dayFromPresent; }
    public boolean dayToPresent() { return dayToPresent; }
    public boolean unitsPresent() { return unitsPresent; }
    public boolean sortOrderPresent() { return sortOrderPresent; }
    public boolean deadlinePresent() { return deadlinePresent; }
    public boolean estPerPersonPresent() { return estPerPersonPresent; }
    public boolean statusPresent() { return statusPresent; }

    public void setVersion(Long value) { this.version = value; }
    public void setTitle(String value) { this.title = value; this.titlePresent = true; }
    public void setDayFrom(Integer value) { this.dayFrom = value; this.dayFromPresent = true; }
    public void setDayTo(Integer value) { this.dayTo = value; this.dayToPresent = true; }
    public void setUnits(Integer value) { this.units = value; this.unitsPresent = true; }
    public void setSortOrder(Integer value) { this.sortOrder = value; this.sortOrderPresent = true; }
    public void setDeadline(LocalDate value) { this.deadline = value; this.deadlinePresent = true; }
    public void setEstPerPerson(Long value) { this.estPerPerson = value; this.estPerPersonPresent = true; }
    public void setStatus(SlotStatus value) { this.status = value; this.statusPresent = true; }
}
