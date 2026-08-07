package app.tabikime.kimetabi.expense;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.tabikime.kimetabi.storage.ReceiptStorageGateway;
import app.tabikime.kimetabi.storage.ReceiptStorageProperties;
import app.tabikime.kimetabi.support.event.OutboxEventWriter;
import app.tabikime.kimetabi.trip.MemberRole;
import app.tabikime.kimetabi.trip.TripAuthorizationService;
import app.tabikime.kimetabi.trip.TripForbiddenException;
import app.tabikime.kimetabi.trip.TripNotFoundException;
import app.tabikime.kimetabi.trip.TripPermission;
import app.tabikime.kimetabi.trip.TripValidationException;

@Service
class ExpenseReceiptUploadService {

    private final ExpenseRepository expenseRepository;
    private final ExpenseReceiptRepository receiptRepository;
    private final TripAuthorizationService authorization;
    private final ReceiptStorageGateway storage;
    private final ReceiptStorageProperties properties;
    private final Clock clock;
    private final AuditEventWriter auditWriter;
    private final OutboxEventWriter eventWriter;

    ExpenseReceiptUploadService(
            ExpenseRepository expenseRepository,
            ExpenseReceiptRepository receiptRepository,
            TripAuthorizationService authorization,
            ReceiptStorageGateway storage,
            ReceiptStorageProperties properties,
            Clock clock,
            AuditEventWriter auditWriter,
            OutboxEventWriter eventWriter
    ) {
        this.expenseRepository = expenseRepository;
        this.receiptRepository = receiptRepository;
        this.authorization = authorization;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
        this.auditWriter = auditWriter;
        this.eventWriter = eventWriter;
    }

    @Transactional
    ReceiptUploadResource prepare(
            String firebaseUid,
            long tripId,
            long expenseId,
            PrepareReceiptUploadRequest request
    ) {
        validatePrepare(request);
        var actor = authorization.requireActor(firebaseUid, tripId, TripPermission.ADD_EXPENSE);
        ExpenseResource expense = expenseRepository.lock(tripId, expenseId)
                .orElseThrow(TripNotFoundException::new);
        requireEditableDraft(actor, expense);
        requireVersion(request.version(), expense);

        UUID receiptId = UUID.randomUUID();
        String objectKey = "receipts/" + tripId + "/" + expenseId + "/" + receiptId;
        var capability = storage.createUploadCapability(
                objectKey, request.contentType(), request.byteSize(), properties.uploadUrlTtl());
        receiptRepository.insert(
                receiptId, tripId, expenseId, objectKey,
                request.contentType(), request.byteSize());
        if (!expenseRepository.incrementVersion(tripId, expenseId, expense.version())) {
            throw new ExpenseVersionConflictException(
                    expenseRepository.find(tripId, expenseId)
                            .orElseThrow(TripNotFoundException::new));
        }
        ExpenseResource updated = expenseRepository.find(tripId, expenseId).orElseThrow();
        auditWriter.write(tripId, actor.id(), "EXPENSE_RECEIPT_UPLOAD_PREPARED", expense, updated);
        long revision = eventWriter.nextRevision(tripId);
        eventWriter.write(
                tripId, revision, "EXPENSE_RECEIPT_UPLOAD_PREPARED", "expense",
                updated.id(), updated.version());
        return new ReceiptUploadResource(
                receiptId,
                capability.url(),
                OffsetDateTime.now(clock).plus(properties.uploadUrlTtl()),
                capability.requiredHeaders(),
                updated.version());
    }

    @Transactional
    ExpenseResource complete(
            String firebaseUid,
            long tripId,
            long expenseId,
            UUID receiptId,
            CompleteReceiptUploadRequest request
    ) {
        if (request.version() < 0) throw invalid("version", "versionは0以上にしてください。");
        var actor = authorization.requireActor(firebaseUid, tripId, TripPermission.ADD_EXPENSE);
        ExpenseResource expense = expenseRepository.lock(tripId, expenseId)
                .orElseThrow(TripNotFoundException::new);
        requireEditableDraft(actor, expense);
        requireVersion(request.version(), expense);
        var receipt = receiptRepository.lock(tripId, expenseId, receiptId)
                .orElseThrow(TripNotFoundException::new);
        if ("UPLOADED".equals(receipt.status())) return expense;

        var stored = storage.find(receipt.objectKey())
                .orElseThrow(() -> invalid("receiptId", "アップロード済み画像が見つかりません。"));
        if (!receipt.contentType().equals(stored.contentType())) {
            throw invalid("contentType", "保存された画像のMIME typeが一致しません。");
        }
        if (receipt.byteSize() != stored.byteSize()) {
            throw invalid("byteSize", "保存された画像の容量が一致しません。");
        }
        if (stored.byteSize() < 1 || stored.byteSize() > 10_485_760) {
            throw invalid("byteSize", "画像は10 MiB以下にしてください。");
        }
        receiptRepository.markUploaded(receiptId);
        if (!expenseRepository.incrementVersion(tripId, expenseId, expense.version())) {
            throw new ExpenseVersionConflictException(
                    expenseRepository.find(tripId, expenseId)
                            .orElseThrow(TripNotFoundException::new));
        }
        ExpenseResource updated = expenseRepository.find(tripId, expenseId).orElseThrow();
        auditWriter.write(tripId, actor.id(), "EXPENSE_RECEIPT_UPLOADED", expense, updated);
        long revision = eventWriter.nextRevision(tripId);
        eventWriter.write(
                tripId, revision, "EXPENSE_RECEIPT_UPLOADED", "expense",
                updated.id(), updated.version());
        return updated;
    }

    private void requireEditableDraft(
            TripAuthorizationService.AuthorizedMember actor,
            ExpenseResource expense
    ) {
        if (expense.status() != ExpenseStatus.DRAFT) {
            throw new ExpenseStateConflictException("確定済み支出へ画像を追加できません。");
        }
        if (actor.role() == MemberRole.MEMBER && actor.id() != expense.createdByMemberId()) {
            throw new TripForbiddenException();
        }
    }

    private void requireVersion(long version, ExpenseResource expense) {
        if (version != expense.version()) throw new ExpenseVersionConflictException(expense);
    }

    private void validatePrepare(PrepareReceiptUploadRequest request) {
        if (!java.util.Set.of("image/jpeg", "image/png", "image/webp")
                .contains(request.contentType())) {
            throw invalid("contentType", "JPEG、PNG、WebPのいずれかを指定してください。");
        }
        if (request.byteSize() < 1 || request.byteSize() > 10_485_760) {
            throw invalid("byteSize", "画像は1 byte以上10 MiB以下にしてください。");
        }
        if (request.version() < 0) throw invalid("version", "versionは0以上にしてください。");
    }

    private TripValidationException invalid(String field, String message) {
        return new TripValidationException(field, message);
    }
}
