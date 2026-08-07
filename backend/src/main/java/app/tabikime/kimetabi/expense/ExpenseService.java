package app.tabikime.kimetabi.expense;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.tabikime.kimetabi.support.event.OutboxEventWriter;
import app.tabikime.kimetabi.trip.MemberRole;
import app.tabikime.kimetabi.trip.TripAuthorizationService;
import app.tabikime.kimetabi.trip.TripNotFoundException;
import app.tabikime.kimetabi.trip.TripPermission;
import app.tabikime.kimetabi.trip.TripForbiddenException;
import app.tabikime.kimetabi.trip.TripValidationException;

@Service
class ExpenseService {

    private final ExpenseRepository repository;
    private final TripAuthorizationService authorization;
    private final OutboxEventWriter eventWriter;
    private final AuditEventWriter auditWriter;

    ExpenseService(
            ExpenseRepository repository,
            TripAuthorizationService authorization,
            OutboxEventWriter eventWriter,
            AuditEventWriter auditWriter
    ) {
        this.repository = repository;
        this.authorization = authorization;
        this.eventWriter = eventWriter;
        this.auditWriter = auditWriter;
    }

    @Transactional(readOnly = true)
    ExpenseResource get(String firebaseUid, long tripId, long expenseId) {
        authorization.require(firebaseUid, tripId, TripPermission.VIEW_TRIP);
        return repository.find(tripId, expenseId).orElseThrow(TripNotFoundException::new);
    }

    @Transactional(readOnly = true)
    ExpensePage list(
            String firebaseUid,
            long tripId,
            String cursor,
            int limit,
            ExpenseStatus status
    ) {
        authorization.require(firebaseUid, tripId, TripPermission.VIEW_TRIP);
        ExpenseCursor.Decoded decoded = cursor == null ? null : ExpenseCursor.decode(cursor);
        List<ExpenseRepository.ListedExpense> rows = repository.list(
                tripId,
                status,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.id(),
                limit + 1);
        boolean hasNext = rows.size() > limit;
        List<ExpenseRepository.ListedExpense> pageRows = hasNext
                ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasNext) {
            ExpenseRepository.ListedExpense last = pageRows.get(pageRows.size() - 1);
            nextCursor = ExpenseCursor.encode(last.createdAt(), last.resource().id());
        }
        return new ExpensePage(
                pageRows.stream().map(ExpenseRepository.ListedExpense::resource).toList(),
                nextCursor);
    }

    @Transactional(readOnly = true)
    Optional<ExpenseSharePresetResource> previousSharePreset(String firebaseUid, long tripId) {
        authorization.require(firebaseUid, tripId, TripPermission.VIEW_TRIP);
        return repository.findLatestConfirmed(tripId).map(expense ->
                new ExpenseSharePresetResource(
                        expense.id(),
                        expense.allocationType(),
                        toInputs(expense.shares())));
    }

    @Transactional
    ExpenseResource create(
            String firebaseUid,
            long tripId,
            CreateExpenseDraftRequest request
    ) {
        long actorMemberId = authorization.requireMemberId(
                firebaseUid, tripId, TripPermission.ADD_EXPENSE);
        validateCreate(tripId, request);
        ExpenseSource source = request.source() == null ? ExpenseSource.MANUAL : request.source();
        long expenseId = repository.insert(tripId, actorMemberId, request, source);
        ExpenseResource expense = repository.find(tripId, expenseId).orElseThrow();
        long revision = eventWriter.nextRevision(tripId);
        eventWriter.write(
                tripId, revision, "EXPENSE_DRAFT_CREATED", "expense",
                expense.id(), expense.version());
        return expense;
    }

    @Transactional
    ExpenseResource update(
            String firebaseUid,
            long tripId,
            long expenseId,
            UpdateExpenseRequest request
    ) {
        TripAuthorizationService.AuthorizedMember actor = authorization.requireActor(
                firebaseUid, tripId, TripPermission.ADD_EXPENSE);
        ExpenseResource current = repository.lock(tripId, expenseId)
                .orElseThrow(TripNotFoundException::new);
        requireEditable(actor, current);
        if (request.version() != current.version()) {
            throw new ExpenseVersionConflictException(current);
        }
        validatePatchShape(request);

        Long payerId = request.payerIdPresent() ? request.payerId() : current.payerId();
        Long amount = request.amountPresent() ? request.amount() : current.amount();
        var paidAt = request.paidAtPresent() ? request.paidAt() : current.paidAt();
        AllocationType allocationType = request.allocationTypePresent()
                ? request.allocationType() : current.allocationType();
        ExpenseStatus status = request.statusPresent() ? request.status() : current.status();
        List<ExpenseShareInput> shareInputs = request.sharesPresent()
                ? request.shares() : toInputs(current.shares());

        if (payerId != null && !repository.membersBelongToTrip(tripId, List.of(payerId))) {
            throw invalid("payerId", "支払者は旅行のメンバーから選択してください。");
        }
        if (!shareInputs.isEmpty() && !repository.membersBelongToTrip(
                tripId, shareInputs.stream().map(ExpenseShareInput::memberId).toList())) {
            throw invalid("shares", "負担者は同じ旅行のメンバーから選択してください。");
        }
        if (shareInputs.size() != new HashSet<>(shareInputs.stream()
                .map(ExpenseShareInput::memberId).toList()).size()) {
            throw invalid("shares", "同じ負担者を複数回指定できません。");
        }

        List<ExpenseShareResource> shares;
        if (status == ExpenseStatus.CONFIRMED) {
            validateConfirmation(payerId, amount, paidAt, allocationType, shareInputs);
            shares = ExpenseAllocation.calculate(amount, allocationType, shareInputs);
        } else {
            shares = draftShares(shareInputs);
        }
        if (!repository.update(
                tripId, expenseId, current.version(), payerId, amount,
                paidAt, allocationType, status)) {
            throw new ExpenseVersionConflictException(
                    repository.find(tripId, expenseId).orElseThrow(TripNotFoundException::new));
        }
        repository.replaceShares(tripId, expenseId, shares);
        ExpenseResource updated = repository.find(tripId, expenseId).orElseThrow();
        validatePersistedConfirmation(updated);
        String action = action(current, updated);
        auditWriter.write(tripId, actor.id(), action, current, updated);
        long revision = eventWriter.nextRevision(tripId);
        eventWriter.write(
                tripId, revision, eventType(current, updated), "expense",
                updated.id(), updated.version());
        return updated;
    }

    @Transactional
    void deleteDraft(
            String firebaseUid,
            long tripId,
            long expenseId,
            long version
    ) {
        TripAuthorizationService.AuthorizedMember actor = authorization.requireActor(
                firebaseUid, tripId, TripPermission.ADD_EXPENSE);
        ExpenseResource current = repository.lock(tripId, expenseId)
                .orElseThrow(TripNotFoundException::new);
        requireEditable(actor, current);
        if (current.status() != ExpenseStatus.DRAFT) {
            throw new ExpenseStateConflictException(
                    "確定済み支出は削除できません。訂正してください。");
        }
        if (version != current.version()) {
            throw new ExpenseVersionConflictException(current);
        }
        auditWriter.write(tripId, actor.id(), "EXPENSE_DRAFT_DELETED", current, null);
        if (!repository.deleteDraft(tripId, expenseId, version)) {
            throw new ExpenseVersionConflictException(
                    repository.find(tripId, expenseId).orElseThrow(TripNotFoundException::new));
        }
        long revision = eventWriter.nextRevision(tripId);
        eventWriter.write(
                tripId, revision, "EXPENSE_DRAFT_DELETED", "expense",
                current.id(), current.version());
    }

    private void validateCreate(long tripId, CreateExpenseDraftRequest request) {
        if (request.amount() == null && !Boolean.TRUE.equals(request.hasReceipt())) {
            throw invalid("request", "金額またはレシート画像のどちらかが必要です。");
        }
        ExpenseSource source = request.source() == null ? ExpenseSource.MANUAL : request.source();
        if (source == ExpenseSource.PLAN && request.planItemId() == null) {
            throw invalid("planItemId", "予定由来の支出にはplanItemIdが必要です。");
        }
        if (request.planItemId() != null
                && !repository.planItemBelongsToTrip(tripId, request.planItemId())) {
            throw new TripNotFoundException();
        }
    }

    private void validatePatchShape(UpdateExpenseRequest request) {
        if (!request.payerIdPresent()
                && !request.amountPresent()
                && !request.paidAtPresent()
                && !request.allocationTypePresent()
                && !request.sharesPresent()
                && !request.statusPresent()) {
            throw invalid("request", "version以外に少なくとも1項目を指定してください。");
        }
        if ((request.payerIdPresent() && request.payerId() == null)
                || (request.amountPresent() && request.amount() == null)
                || (request.paidAtPresent() && request.paidAt() == null)
                || (request.allocationTypePresent() && request.allocationType() == null)
                || (request.sharesPresent() && request.shares() == null)
                || (request.statusPresent() && request.status() != ExpenseStatus.CONFIRMED)) {
            throw invalid("request", "指定した項目にnullまたは許可されていない値があります。");
        }
    }

    private void validateConfirmation(
            Long payerId,
            Long amount,
            java.time.OffsetDateTime paidAt,
            AllocationType allocationType,
            List<ExpenseShareInput> shares
    ) {
        if (payerId == null) throw invalid("payerId", "確定時は支払者が必要です。");
        if (amount == null || amount <= 0) {
            throw invalid("amount", "確定時は正の支出額が必要です。");
        }
        if (paidAt == null) throw invalid("paidAt", "確定時は支払日時が必要です。");
        if (allocationType == null) {
            throw invalid("allocationType", "確定時は按分方式が必要です。");
        }
        if (shares.isEmpty()) throw invalid("shares", "確定時は負担者が必要です。");
    }

    private void validatePersistedConfirmation(ExpenseResource expense) {
        if (expense.status() != ExpenseStatus.CONFIRMED) return;
        long total = expense.shares().stream()
                .map(ExpenseShareResource::finalAmount)
                .reduce(0L, Math::addExact);
        if (total != expense.baseAmount()) {
            throw new IllegalStateException("Persisted expense shares do not sum to base amount");
        }
    }

    private void requireEditable(
            TripAuthorizationService.AuthorizedMember actor,
            ExpenseResource expense
    ) {
        if (expense.status() == ExpenseStatus.CONFIRMED) {
            if (actor.role() == MemberRole.MEMBER) {
                throw new TripForbiddenException();
            }
            return;
        }
        if (actor.id() != expense.createdByMemberId() && actor.role() == MemberRole.MEMBER) {
            throw new TripForbiddenException();
        }
    }

    private List<ExpenseShareInput> toInputs(List<ExpenseShareResource> shares) {
        return shares.stream()
                .map(share -> new ExpenseShareInput(
                        share.memberId(), share.weight(), share.fixedAmount()))
                .toList();
    }

    private List<ExpenseShareResource> draftShares(List<ExpenseShareInput> inputs) {
        List<ExpenseShareResource> result = new ArrayList<>();
        for (ExpenseShareInput input : inputs) {
            result.add(new ExpenseShareResource(
                    input.memberId(), input.weight(), input.fixedAmount(), null));
        }
        return List.copyOf(result);
    }

    private String action(ExpenseResource before, ExpenseResource after) {
        if (before.status() == ExpenseStatus.DRAFT
                && after.status() == ExpenseStatus.CONFIRMED) {
            return "EXPENSE_CONFIRMED";
        }
        return before.status() == ExpenseStatus.CONFIRMED
                ? "EXPENSE_CORRECTED" : "EXPENSE_DRAFT_UPDATED";
    }

    private String eventType(ExpenseResource before, ExpenseResource after) {
        return before.status() == ExpenseStatus.DRAFT
                && after.status() == ExpenseStatus.CONFIRMED
                ? "EXPENSE_CONFIRMED" : "EXPENSE_UPDATED";
    }

    private TripValidationException invalid(String field, String message) {
        return new TripValidationException(field, message);
    }
}
