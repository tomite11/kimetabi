package app.tabikime.kimetabi.candidate;

import java.util.List;

public record CandidateResource(
        long id,
        long slotId,
        long createdByMemberId,
        String title,
        String url,
        String imageUrl,
        String note,
        List<String> tags,
        Long estAmount,
        EstimateBasis estBasis,
        CandidateStatus status,
        MetadataStatus metadataStatus,
        String metadataErrorCode,
        long version
) {
}
