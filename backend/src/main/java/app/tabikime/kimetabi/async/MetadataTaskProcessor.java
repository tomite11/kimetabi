package app.tabikime.kimetabi.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import app.tabikime.kimetabi.candidate.CandidateMetadataResult;
import app.tabikime.kimetabi.candidate.CandidateService;
import app.tabikime.kimetabi.candidate.MetadataFailureType;
import app.tabikime.kimetabi.ingestion.metadata.MetadataExtractionException;
import app.tabikime.kimetabi.ingestion.metadata.UrlMetadataExtractor;

@Service
public class MetadataTaskProcessor {

    private static final Logger logger = LoggerFactory.getLogger(MetadataTaskProcessor.class);

    private final CandidateService candidateService;
    private final UrlMetadataExtractor extractor;

    MetadataTaskProcessor(
            CandidateService candidateService,
            UrlMetadataExtractor extractor
    ) {
        this.candidateService = candidateService;
        this.extractor = extractor;
    }

    public ProcessingResult process(MetadataTaskRequest request) {
        var work = candidateService.startMetadataProcessing(
                request.eventId(), request.candidateId());
        if (work.isEmpty()) {
            return ProcessingResult.DUPLICATE;
        }
        try {
            var metadata = extractor.extract(work.get().url());
            candidateService.completeMetadata(
                    request.eventId(),
                    request.candidateId(),
                    new CandidateMetadataResult(metadata.title(), metadata.imageUrl()));
            return ProcessingResult.COMPLETED;
        } catch (MetadataExtractionException exception) {
            logger.info(
                    "Metadata task failed eventId={} candidateId={} outcome={} retryable={}",
                    request.eventId(), request.candidateId(), exception.errorCode(),
                    exception.disposition() == MetadataExtractionException.Disposition.RETRYABLE);
            MetadataFailureType failureType = exception.disposition()
                    == MetadataExtractionException.Disposition.RETRYABLE
                    ? MetadataFailureType.RETRYABLE
                    : MetadataFailureType.PERMANENT;
            candidateService.failMetadata(
                    request.eventId(), request.candidateId(), failureType, exception.errorCode());
            return failureType == MetadataFailureType.RETRYABLE
                    ? ProcessingResult.RETRYABLE_FAILURE
                    : ProcessingResult.PERMANENT_FAILURE;
        }
    }

    public enum ProcessingResult {
        COMPLETED,
        DUPLICATE,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }
}
