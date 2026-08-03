package app.tabikime.kimetabi.candidate;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class UpdateCandidateRequest {

    @NotNull
    @Min(0)
    private Long version;
    @Size(max = 200)
    private String title;
    @Size(max = 2048)
    private String url;
    @Size(max = 2000)
    private String note;
    @Size(max = 20)
    private List<@Size(min = 1, max = 40) String> tags;
    @Min(0)
    private Long estAmount;
    private EstimateBasis estBasis;
    private CandidateStatus status;
    private boolean titlePresent;
    private boolean urlPresent;
    private boolean notePresent;
    private boolean tagsPresent;
    private boolean estAmountPresent;
    private boolean estBasisPresent;
    private boolean statusPresent;

    public Long version() { return version; }
    public String title() { return title; }
    public String url() { return url; }
    public String note() { return note; }
    public List<String> tags() { return tags; }
    public Long estAmount() { return estAmount; }
    public EstimateBasis estBasis() { return estBasis; }
    public CandidateStatus status() { return status; }
    public boolean titlePresent() { return titlePresent; }
    public boolean urlPresent() { return urlPresent; }
    public boolean notePresent() { return notePresent; }
    public boolean tagsPresent() { return tagsPresent; }
    public boolean estAmountPresent() { return estAmountPresent; }
    public boolean estBasisPresent() { return estBasisPresent; }
    public boolean statusPresent() { return statusPresent; }

    public void setVersion(Long version) { this.version = version; }
    public void setTitle(String title) { this.title = title; this.titlePresent = true; }
    public void setUrl(String url) { this.url = url; this.urlPresent = true; }
    public void setNote(String note) { this.note = note; this.notePresent = true; }
    public void setTags(List<String> tags) { this.tags = tags; this.tagsPresent = true; }
    public void setEstAmount(Long value) { this.estAmount = value; this.estAmountPresent = true; }
    public void setEstBasis(EstimateBasis value) { this.estBasis = value; this.estBasisPresent = true; }
    public void setStatus(CandidateStatus value) { this.status = value; this.statusPresent = true; }
}
