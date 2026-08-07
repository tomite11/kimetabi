package app.tabikime.kimetabi.expense;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.tabikime.kimetabi.storage.ReceiptStorageGateway;
import app.tabikime.kimetabi.storage.ReceiptStorageProperties;
import app.tabikime.kimetabi.support.event.OutboxEventWriter;
import app.tabikime.kimetabi.trip.TripNotFoundException;

@Service
class ReceiptOrphanCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(ReceiptOrphanCleanupService.class);

    private final ExpenseReceiptRepository receiptRepository;
    private final ExpenseRepository expenseRepository;
    private final ReceiptStorageGateway storage;
    private final ReceiptStorageProperties properties;
    private final OutboxEventWriter eventWriter;
    private final Clock clock;

    ReceiptOrphanCleanupService(
            ExpenseReceiptRepository receiptRepository,
            ExpenseRepository expenseRepository,
            ReceiptStorageGateway storage,
            ReceiptStorageProperties properties,
            OutboxEventWriter eventWriter,
            Clock clock
    ) {
        this.receiptRepository = receiptRepository;
        this.expenseRepository = expenseRepository;
        this.storage = storage;
        this.properties = properties;
        this.eventWriter = eventWriter;
        this.clock = clock;
    }

    @Transactional
    CleanupResult cleanup() {
        Instant cutoff = clock.instant().minus(properties.orphanRetention());
        var candidates = receiptRepository.findOrphanCandidates(
                cutoff, properties.orphanCleanupBatchSize());
        int deleted = 0;
        for (var candidate : candidates) {
            ExpenseResource before = expenseRepository.lock(
                    candidate.tripId(), candidate.expenseId()).orElse(null);
            if (before == null) continue;
            var orphan = receiptRepository.lockOrphan(candidate.id(), cutoff).orElse(null);
            if (orphan == null) continue;
            storage.delete(orphan.objectKey());
            if (!receiptRepository.deleteOrphan(orphan.id())) continue;
            if (!expenseRepository.incrementVersion(
                    orphan.tripId(), orphan.expenseId(), before.version())) {
                throw new ExpenseVersionConflictException(
                        expenseRepository.find(orphan.tripId(), orphan.expenseId())
                                .orElseThrow(TripNotFoundException::new));
            }
            ExpenseResource updated = expenseRepository.find(
                    orphan.tripId(), orphan.expenseId()).orElseThrow();
            long revision = eventWriter.nextRevision(orphan.tripId());
            eventWriter.write(
                    orphan.tripId(), revision, "EXPENSE_RECEIPT_ORPHAN_CLEANED", "expense",
                    updated.id(), updated.version());
            deleted++;
            logger.info(
                    "Receipt orphan cleanup completed receiptId={} tripId={} expenseId={} outcome=DELETED",
                    orphan.id(), orphan.tripId(), orphan.expenseId());
        }
        logger.info(
                "Receipt orphan cleanup batch completed selected={} deleted={} outcome=SUCCESS",
                candidates.size(), deleted);
        return new CleanupResult(candidates.size(), deleted);
    }

    record CleanupResult(int selected, int deleted) {
    }
}
