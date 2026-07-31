package app.tabikime.kimetabi.trip;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class UpdateTripRequest {

    @NotNull
    @PositiveOrZero
    private Long version;

    @Size(min = 1, max = 200)
    private String title;

    @Size(min = 1, max = 200)
    private String destination;

    private LocalDate startsOn;
    private LocalDate endsOn;

    @Size(min = 1, max = 40)
    private String timezone;

    @Min(1)
    @Max(100)
    private Integer expectedMemberCount;

    private TripPhase phaseOverride;
    private boolean phaseOverridePresent;
    private VoteVisibility voteVisibility;

    @PositiveOrZero
    private Long budgetCap;
    private boolean budgetCapPresent;

    public Long version() {
        return version;
    }

    public String title() {
        return title;
    }

    public String destination() {
        return destination;
    }

    public LocalDate startsOn() {
        return startsOn;
    }

    public LocalDate endsOn() {
        return endsOn;
    }

    public String timezone() {
        return timezone;
    }

    public Integer expectedMemberCount() {
        return expectedMemberCount;
    }

    public TripPhase phaseOverride() {
        return phaseOverride;
    }

    public boolean phaseOverridePresent() {
        return phaseOverridePresent;
    }

    public VoteVisibility voteVisibility() {
        return voteVisibility;
    }

    public Long budgetCap() {
        return budgetCap;
    }

    public boolean budgetCapPresent() {
        return budgetCapPresent;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public void setStartsOn(LocalDate startsOn) {
        this.startsOn = startsOn;
    }

    public void setEndsOn(LocalDate endsOn) {
        this.endsOn = endsOn;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public void setExpectedMemberCount(Integer expectedMemberCount) {
        this.expectedMemberCount = expectedMemberCount;
    }

    @JsonSetter("phaseOverride")
    public void setPhaseOverride(TripPhase phaseOverride) {
        this.phaseOverride = phaseOverride;
        this.phaseOverridePresent = true;
    }

    public void setVoteVisibility(VoteVisibility voteVisibility) {
        this.voteVisibility = voteVisibility;
    }

    @JsonSetter("budgetCap")
    public void setBudgetCap(Long budgetCap) {
        this.budgetCap = budgetCap;
        this.budgetCapPresent = true;
    }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown update field: " + field);
    }

    boolean hasChange() {
        return title != null
                || destination != null
                || startsOn != null
                || endsOn != null
                || timezone != null
                || expectedMemberCount != null
                || phaseOverridePresent
                || voteVisibility != null
                || budgetCapPresent;
    }
}
