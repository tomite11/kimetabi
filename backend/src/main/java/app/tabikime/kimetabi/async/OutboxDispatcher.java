package app.tabikime.kimetabi.async;

import java.io.IOException;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import app.tabikime.kimetabi.realtime.TripEventPublisher;

@Service
public class OutboxDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxDispatchRepository repository;
    private final MetadataTaskGateway taskGateway;
    private final TripEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    OutboxDispatcher(
            OutboxDispatchRepository repository,
            MetadataTaskGateway taskGateway,
            TripEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.taskGateway = taskGateway;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.publishedCounter = Counter.builder("kimetabi.outbox.dispatch")
                .tag("outcome", "published")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("kimetabi.outbox.dispatch")
                .tag("outcome", "failed")
                .register(meterRegistry);
        Gauge.builder("kimetabi.outbox.pending", repository,
                        OutboxDispatchRepository::countPendingEvents)
                .register(meterRegistry);
        Gauge.builder("kimetabi.outbox.oldest.pending.age", repository,
                        OutboxDispatchRepository::oldestPendingAgeSeconds)
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    @Transactional
    public DispatchResult dispatch(int limit) {
        int selected = 0;
        int published = 0;
        int failed = 0;
        for (var event : repository.lockPendingEvents(limit)) {
            selected++;
            try {
                String outcomeCode;
                if (event.isMetadataTaskRequest()) {
                    taskGateway.create(event.eventId(), event.resourceId());
                    outcomeCode = "TASK_CREATED";
                } else {
                    eventPublisher.publish(event.tripId(), readPayload(event.payload()));
                    outcomeCode = "STOMP_PUBLISHED";
                }
                repository.markPublished(event.eventId(), outcomeCode);
                published++;
                publishedCounter.increment();
            } catch (IOException | RuntimeException exception) {
                String outcomeCode = event.isMetadataTaskRequest()
                        ? "TASK_CREATE_FAILED"
                        : "STOMP_PUBLISH_FAILED";
                repository.markFailed(event.eventId(), outcomeCode);
                failed++;
                failedCounter.increment();
                logger.warn(
                        "Outbox dispatch failed eventId={} tripId={} resourceId={} outcome={}",
                        event.eventId(), event.tripId(), event.resourceId(), outcomeCode);
            }
        }
        return new DispatchResult(selected, published, failed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, Map.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Invalid persisted outbox payload", exception);
        }
    }

    public record DispatchResult(int selected, int published, int failed) {
    }
}
