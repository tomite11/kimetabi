package app.tabikime.kimetabi.settlement;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.tabikime.kimetabi.support.event.OutboxEventWriter;
import app.tabikime.kimetabi.support.idempotency.IdempotencyStore;
import app.tabikime.kimetabi.trip.InvalidCursorException;
import app.tabikime.kimetabi.trip.MemberRole;
import app.tabikime.kimetabi.trip.TripAuthorizationService;
import app.tabikime.kimetabi.trip.TripNotFoundException;
import app.tabikime.kimetabi.trip.TripPermission;

@Service
class SettlementService {

    private static final int MAX_PAGE_SIZE = 100;
    private final SettlementRepository repository;
    private final TripAuthorizationService authorization;
    private final IdempotencyStore idempotencyStore;
    private final OutboxEventWriter eventWriter;
    private final SettlementAuditEventWriter auditWriter;

    SettlementService(
            SettlementRepository repository,
            TripAuthorizationService authorization,
            IdempotencyStore idempotencyStore,
            OutboxEventWriter eventWriter,
            SettlementAuditEventWriter auditWriter
    ) {
        this.repository = repository;
        this.authorization = authorization;
        this.idempotencyStore = idempotencyStore;
        this.eventWriter = eventWriter;
        this.auditWriter = auditWriter;
    }

    @Transactional
    SettlementResource createDraft(
            String firebaseUid,
            long tripId,
            UUID idempotencyKey,
            CreateSettlementRequest request
    ) {
        long actorId = authorization.requireMemberId(
                firebaseUid, tripId, TripPermission.CREATE_SETTLEMENT);
        String hash = idempotencyStore.hash(new CreateKey(tripId, request.expectedTripRevision()));
        var replay = idempotencyStore.claimOrReplay(
                firebaseUid, "CREATE_SETTLEMENT_DRAFT", idempotencyKey, hash);
        if (replay != null) return idempotencyStore.read(replay, SettlementResource.class);

        long currentRevision = repository.lockTripRevision(tripId);
        if (request.expectedTripRevision() != null
                && request.expectedTripRevision() != currentRevision) {
            throw new SettlementStateConflictException("旅行の支出が更新されています。");
        }
        List<SettlementExpenseSnapshot> expenses = repository.confirmedExpenses(tripId);
        List<SettlementSourceTransferSnapshot> sources = repository.paidTransfers(tripId);
        SettlementCalculation calculation = SettlementCalculator.calculate(expenses, sources);
        long settlementId = repository.insertDraft(tripId, actorId);
        repository.insertSnapshots(
                settlementId, tripId, expenses, sources, calculation.transfers());
        SettlementResource result = resource(tripId, settlementId, false);
        idempotencyStore.complete(firebaseUid, "CREATE_SETTLEMENT_DRAFT", idempotencyKey,
                "SETTLEMENT", settlementId, result);
        return result;
    }

    SettlementResource get(String firebaseUid, long tripId, long settlementId) {
        authorization.requireMembership(firebaseUid, tripId);
        return resource(tripId, settlementId, true);
    }

    SettlementPage list(String firebaseUid, long tripId, String cursor, int pageSize) {
        authorization.requireMembership(firebaseUid, tripId);
        int size = Math.min(pageSize, MAX_PAGE_SIZE);
        long beforeId = decodeCursor(cursor);
        List<SettlementRepository.StoredSettlement> rows = repository.list(tripId, beforeId, size + 1);
        boolean hasNext = rows.size() > size;
        List<SettlementResource> items = rows.stream().limit(size)
                .map(row -> resource(tripId, row.id(), true)).toList();
        String next = hasNext ? encodeCursor(items.get(items.size() - 1).id()) : null;
        return new SettlementPage(items, next);
    }

    @Transactional
    SettlementResource confirm(
            String firebaseUid,
            long tripId,
            long settlementId,
            long version
    ) {
        authorization.requireMemberId(firebaseUid, tripId, TripPermission.CONFIRM_SETTLEMENT);
        repository.lockTripRevision(tripId);
        SettlementRepository.StoredSettlement current = repository.find(tripId, settlementId, true)
                .orElseThrow(TripNotFoundException::new);
        SettlementResource currentResource = resource(tripId, settlementId, true);
        if (current.version() != version) throw new SettlementVersionConflictException(currentResource);
        if (current.status() != SettlementStatus.DRAFT) {
            throw new SettlementStateConflictException("DRAFTの精算だけを確定できます。");
        }
        if (currentResource.hasUnappliedChanges()) {
            throw new SettlementStateConflictException("支出または既払送金が更新されています。再計算してください。");
        }
        boolean complete = currentResource.transfers().isEmpty();
        if (!repository.confirm(tripId, settlementId, version, complete)) {
            throw new SettlementVersionConflictException(resource(tripId, settlementId, true));
        }
        repository.supersedeOthers(tripId, settlementId);
        SettlementResource updated = resource(tripId, settlementId, true);
        long revision = eventWriter.nextRevision(tripId);
        eventWriter.write(tripId, revision, "SETTLEMENT_CONFIRMED",
                "settlement", settlementId, updated.version());
        return updated;
    }

    @Transactional
    SettlementResource updateTransfer(
            String firebaseUid,
            long tripId,
            long settlementId,
            long transferId,
            UpdateTransferRequest request
    ) {
        TripAuthorizationService.AuthorizedMember actor = authorization.requireActor(
                firebaseUid, tripId, TripPermission.VIEW_TRIP);
        repository.lockTripRevision(tripId);
        SettlementRepository.StoredSettlement settlement = repository.find(
                tripId, settlementId, true).orElseThrow(TripNotFoundException::new);
        SettlementTransferResource current = repository.findTransfer(
                tripId, settlementId, transferId, true).orElseThrow(TripNotFoundException::new);
        if (settlement.status() != SettlementStatus.CONFIRMED) {
            throw new SettlementStateConflictException("確定済みの精算だけ送金状態を更新できます。");
        }
        if (current.version() != request.version()) {
            throw new SettlementTransferVersionConflictException(current);
        }
        TransferStatus expected = expectedPreviousStatus(request.status());
        if (current.status() != expected) {
            throw new SettlementStateConflictException("送金状態を指定された状態へ変更できません。");
        }
        boolean administrator = actor.role() == MemberRole.OWNER
                || actor.role() == MemberRole.ORGANIZER;
        long responsibleMemberId = request.status() == TransferStatus.PAID
                ? current.fromMemberId() : current.toMemberId();
        if (actor.id() != responsibleMemberId && !administrator) {
            throw new app.tabikime.kimetabi.trip.TripForbiddenException();
        }
        if (!repository.updateTransferStatus(
                tripId, settlementId, transferId, request.version(), expected, request.status())) {
            throw new SettlementTransferVersionConflictException(repository.findTransfer(
                    tripId, settlementId, transferId, false).orElseThrow(TripNotFoundException::new));
        }
        SettlementTransferResource updatedTransfer = repository.findTransfer(
                tripId, settlementId, transferId, false).orElseThrow();
        if (actor.id() != responsibleMemberId) {
            auditWriter.writeProxyTransferUpdate(tripId, actor.id(), current, updatedTransfer);
        }
        if (request.status() == TransferStatus.CONFIRMED) {
            repository.completeIfAllTransfersConfirmed(tripId, settlementId);
        }
        long revision = eventWriter.nextRevision(tripId);
        eventWriter.write(tripId, revision, "SETTLEMENT_TRANSFER_UPDATED",
                "settlementTransfer", transferId, updatedTransfer.version());
        SettlementResource updated = resource(tripId, settlementId, true);
        return updated;
    }

    private TransferStatus expectedPreviousStatus(TransferStatus target) {
        if (target == TransferStatus.PAID) return TransferStatus.PENDING;
        if (target == TransferStatus.CONFIRMED) return TransferStatus.PAID;
        throw new SettlementStateConflictException("PENDINGへ戻すことはできません。");
    }

    private SettlementResource resource(long tripId, long settlementId, boolean calculateChanges) {
        SettlementRepository.StoredSettlement stored = repository.find(tripId, settlementId, false)
                .orElseThrow(TripNotFoundException::new);
        boolean changed = calculateChanges && repository.hasUnappliedChanges(
                settlementId,
                repository.confirmedExpenses(tripId),
                repository.paidTransfersExcluding(tripId, settlementId));
        return new SettlementResource(
                stored.id(), stored.status(), stored.calculatedAt(),
                repository.expenseTotal(settlementId),
                repository.expenseVersions(settlementId), repository.transfers(settlementId),
                changed, stored.version());
    }

    private long decodeCursor(String cursor) {
        if (cursor == null) return Long.MAX_VALUE;
        try {
            long value = Long.parseLong(new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (IllegalArgumentException exception) {
            throw new InvalidCursorException();
        }
    }

    private String encodeCursor(long id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                Long.toString(id).getBytes(StandardCharsets.US_ASCII));
    }

    private record CreateKey(long tripId, Long expectedTripRevision) {
    }
}
