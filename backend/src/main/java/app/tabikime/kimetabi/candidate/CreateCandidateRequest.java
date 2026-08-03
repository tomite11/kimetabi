package app.tabikime.kimetabi.candidate;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CreateCandidateRequest(
        @Size(max = 200) String title,
        @Size(max = 2048) String url,
        @Size(max = 2000) String note,
        @Size(max = 20) List<@Size(min = 1, max = 40) String> tags,
        @Min(0) Long estAmount,
        EstimateBasis estBasis
) {
}
