package app.tabikime.kimetabi.async;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxDispatchRepository repository;
    private final MetadataTaskGateway taskGateway;

    OutboxDispatcher(
            OutboxDispatchRepository repository,
            MetadataTaskGateway taskGateway
    ) {
        this.repository = repository;
        this.taskGateway = taskGateway;
    }

    @Transactional
    public DispatchResult dispatch(int limit) {
        int selected = 0;
        int published = 0;
        int failed = 0;
        for (var event : repository.lockPendingMetadataEvents(limit)) {
            selected++;
            try {
                taskGateway.create(event.eventId(), event.candidateId());
                repository.markPublished(event.eventId());
                published++;
            } catch (IOException exception) {
                repository.markFailed(event.eventId());
                failed++;
                logger.warn(
                        "Metadata task dispatch failed eventId={} candidateId={} outcome={}",
                        event.eventId(), event.candidateId(), "TASK_CREATE_FAILED");
            }
        }
        return new DispatchResult(selected, published, failed);
    }

    public record DispatchResult(int selected, int published, int failed) {
    }
}
