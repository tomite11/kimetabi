package app.tabikime.kimetabi.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.tabikime.kimetabi.candidate.CandidateMetadataResult;
import app.tabikime.kimetabi.candidate.CandidateService;
import app.tabikime.kimetabi.candidate.MetadataFailureType;
import app.tabikime.kimetabi.candidate.MetadataWork;
import app.tabikime.kimetabi.ingestion.metadata.ExtractedMetadata;
import app.tabikime.kimetabi.ingestion.metadata.MetadataExtractionException;
import app.tabikime.kimetabi.ingestion.metadata.UrlMetadataExtractor;

class MetadataTaskProcessorTest {

    private final CandidateService candidateService = mock(CandidateService.class);
    private final UrlMetadataExtractor extractor = mock(UrlMetadataExtractor.class);
    private final MetadataTaskProcessor processor =
            new MetadataTaskProcessor(candidateService, extractor);
    private final UUID eventId = UUID.randomUUID();
    private final MetadataTaskRequest request = new MetadataTaskRequest(eventId, 42);

    @BeforeEach
    void setUp() {
        when(candidateService.startMetadataProcessing(eventId, 42))
                .thenReturn(Optional.of(new MetadataWork(
                        eventId, 42, "https://public.example/hotel")));
    }

    @Test
    void completesExtractedMetadata() throws Exception {
        when(extractor.extract("https://public.example/hotel"))
                .thenReturn(new ExtractedMetadata(
                        "Hotel", "https://public.example/image.jpg"));

        assertThat(processor.process(request))
                .isEqualTo(MetadataTaskProcessor.ProcessingResult.COMPLETED);
        verify(candidateService).completeMetadata(
                eventId, 42, new CandidateMetadataResult(
                        "Hotel", "https://public.example/image.jpg"));
    }

    @Test
    void duplicateDeliveryDoesNotFetchAgain() throws Exception {
        when(candidateService.startMetadataProcessing(eventId, 42))
                .thenReturn(Optional.empty());

        assertThat(processor.process(request))
                .isEqualTo(MetadataTaskProcessor.ProcessingResult.DUPLICATE);
        verify(extractor, never()).extract("https://public.example/hotel");
    }

    @Test
    void mapsRetryableAndPermanentFailuresToDomainState() throws Exception {
        when(extractor.extract("https://public.example/hotel"))
                .thenThrow(new MetadataExtractionException(
                        MetadataExtractionException.Disposition.RETRYABLE,
                        "DNS_FAILURE", "fixture"));

        assertThat(processor.process(request))
                .isEqualTo(MetadataTaskProcessor.ProcessingResult.RETRYABLE_FAILURE);
        verify(candidateService).failMetadata(
                eventId, 42, MetadataFailureType.RETRYABLE, "DNS_FAILURE");
    }

    @Test
    void recordsTimeoutAsRetryableFailure() throws Exception {
        when(extractor.extract("https://public.example/hotel"))
                .thenThrow(new MetadataExtractionException(
                        MetadataExtractionException.Disposition.RETRYABLE,
                        "FETCH_TIMEOUT", "fixture"));

        assertThat(processor.process(request))
                .isEqualTo(MetadataTaskProcessor.ProcessingResult.RETRYABLE_FAILURE);
        verify(candidateService).failMetadata(
                eventId, 42, MetadataFailureType.RETRYABLE, "FETCH_TIMEOUT");
    }
}
