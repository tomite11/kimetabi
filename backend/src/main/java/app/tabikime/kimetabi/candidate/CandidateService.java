package app.tabikime.kimetabi.candidate;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.tabikime.kimetabi.trip.SlotDeadlineCalculator;
import app.tabikime.kimetabi.trip.TripAuthorizationService;
import app.tabikime.kimetabi.trip.TripNotFoundException;
import app.tabikime.kimetabi.trip.TripPermission;
import app.tabikime.kimetabi.trip.TripValidationException;
import app.tabikime.kimetabi.trip.VoteVisibility;
import app.tabikime.kimetabi.support.event.OutboxEventWriter;
import app.tabikime.kimetabi.support.idempotency.IdempotencyStore;

@Service
public class CandidateService {

    private static final Pattern METADATA_ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,50}");

    private final CandidateRepository repository;
    private final TripAuthorizationService authorization;
    private final SlotDeadlineCalculator deadlineCalculator;
    private final IdempotencyStore idempotencyStore;
    private final OutboxEventWriter eventWriter;

    CandidateService(
            CandidateRepository repository,
            TripAuthorizationService authorization,
            SlotDeadlineCalculator deadlineCalculator,
            IdempotencyStore idempotencyStore,
            OutboxEventWriter eventWriter
    ) {
        this.repository = repository;
        this.authorization = authorization;
        this.deadlineCalculator = deadlineCalculator;
        this.idempotencyStore = idempotencyStore;
        this.eventWriter = eventWriter;
    }

    @Transactional(readOnly = true)
    public SlotDetail getSlot(String firebaseUid, long tripId, long slotId) {
        long memberId = authorization.requireSlotResourceMemberId(
                firebaseUid, tripId, TripPermission.VIEW_TRIP, slotId);
        List<CandidateResource> candidates = repository.list(tripId, slotId);
        Map<String, VoteView> votes = new LinkedHashMap<>();
        for (CandidateResource candidate : candidates) {
            votes.put(Long.toString(candidate.id()), voteView(tripId, candidate.id(), memberId));
        }
        return new SlotDetail(
                repository.findSlot(tripId, slotId).orElseThrow(TripNotFoundException::new),
                candidates,
                Map.copyOf(votes));
    }

    @Transactional
    public app.tabikime.kimetabi.trip.SlotResource createSlot(
            String firebaseUid,
            long tripId,
            CreateSlotRequest request
    ) {
        authorization.require(firebaseUid, tripId, TripPermission.CHANGE_DEADLINE);
        repository.lockTrip(tripId);
        validateSlotDays(tripId, request.dayFrom(), request.dayTo());
        if (request.sortOrder() > repository.slotCount(tripId)) {
            throw new TripValidationException(
                    "sortOrder", "sortOrderは現在の枠数以下にしてください。");
        }
        CreateSlotRequest resolvedRequest = withDefaultDeadline(tripId, request);
        long slotId = repository.insertSlot(tripId, resolvedRequest);
        return repository.findSlot(tripId, slotId).orElseThrow();
    }

    @Transactional
    public List<app.tabikime.kimetabi.trip.SlotResource> reorderSlots(
            String firebaseUid, long tripId, ReorderSlotsRequest request
    ) {
        authorization.require(firebaseUid, tripId, TripPermission.CHANGE_DEADLINE);
        repository.lockTrip(tripId);
        List<app.tabikime.kimetabi.trip.SlotResource> current = repository.listSlots(tripId);
        if (request.items().size() != current.size()
                || new HashSet<>(request.items().stream().map(ReorderSlotsRequest.Item::slotId)
                        .toList()).size() != current.size()
                || !new HashSet<>(request.items().stream().map(ReorderSlotsRequest.Item::sortOrder)
                        .toList()).equals(java.util.stream.IntStream.range(0, current.size())
                                .boxed().collect(Collectors.toSet()))) {
            throw new TripValidationException("items", "全枠を重複なく連続した順序で指定してください。");
        }
        Map<Long, app.tabikime.kimetabi.trip.SlotResource> currentById = current.stream()
                .collect(Collectors.toMap(app.tabikime.kimetabi.trip.SlotResource::id, slot -> slot));
        for (ReorderSlotsRequest.Item item : request.items()) {
            var slot = currentById.get(item.slotId());
            if (slot == null) throw new TripNotFoundException();
            if (slot.version() != item.version()) throw new SlotVersionConflictException(slot);
        }
        if (!repository.incrementTripVersion(tripId, request.tripVersion())) {
            throw new SlotVersionConflictException(current.get(0));
        }
        repository.reorderSlots(tripId, request.items().stream().collect(Collectors.toMap(
                ReorderSlotsRequest.Item::slotId, ReorderSlotsRequest.Item::sortOrder)));
        return repository.listSlots(tripId);
    }

    @Transactional
    public List<app.tabikime.kimetabi.trip.SlotResource> splitSlot(
            String firebaseUid, long tripId, long slotId, SplitSlotRequest request
    ) {
        authorization.requireSlotResource(
                firebaseUid, tripId, TripPermission.CHANGE_DEADLINE, slotId);
        repository.lockTrip(tripId);
        var slot = repository.lockSlot(tripId, slotId).orElseThrow(TripNotFoundException::new);
        if (slot.version() != request.version()) throw new SlotVersionConflictException(slot);
        if (slot.category() != app.tabikime.kimetabi.trip.SlotCategory.LODGING) {
            throw new TripValidationException("slotId", "宿泊枠だけを分割できます。");
        }
        if (request.splitAfterDay() < slot.dayFrom() || request.splitAfterDay() >= slot.dayTo()) {
            throw new TripValidationException("splitAfterDay", "枠の日付範囲内で分割位置を指定してください。");
        }
        if (request.secondTitle() != null && request.secondTitle().isBlank()) {
            throw new TripValidationException("secondTitle", "後半の枠名は空白にできません。");
        }
        if (repository.slotHasCandidates(tripId, slotId) || slot.adoptedCandidateId() != null) {
            throw new TripValidationException("slotId", "候補または採択予定がある枠は分割できません。");
        }
        int firstUnits = request.splitAfterDay() - slot.dayFrom() + 1;
        int secondUnits = slot.dayTo() - request.splitAfterDay();
        Long firstEstimate = slot.estPerPerson() == null ? null
                : Math.floorDiv(Math.multiplyExact(slot.estPerPerson(), firstUnits), firstUnits + secondUnits);
        Long secondEstimate = slot.estPerPerson() == null ? null : slot.estPerPerson() - firstEstimate;
        if (!repository.shortenSplitSource(
                tripId, slotId, request.version(), request.splitAfterDay(), firstEstimate)) {
            throw new SlotVersionConflictException(repository.findSlot(tripId, slotId).orElseThrow());
        }
        long secondId = repository.insertSplitSlot(tripId, slot, request, secondEstimate);
        return List.of(repository.findSlot(tripId, slotId).orElseThrow(),
                repository.findSlot(tripId, secondId).orElseThrow());
    }

    @Transactional
    public app.tabikime.kimetabi.trip.SlotResource updateSlot(
            String firebaseUid,
            long tripId,
            long slotId,
            UpdateSlotRequest request
    ) {
        authorization.requireSlotResource(
                firebaseUid, tripId, TripPermission.CHANGE_DEADLINE, slotId);
        repository.lockTrip(tripId);
        var current = repository.findSlot(tripId, slotId).orElseThrow();
        if (!hasSlotChange(request)) {
            throw new TripValidationException(
                    "request", "version以外に少なくとも1項目を指定してください。");
        }
        if ((request.titlePresent() && request.title() == null)
                || (request.dayFromPresent() && request.dayFrom() == null)
                || (request.dayToPresent() && request.dayTo() == null)
                || (request.unitsPresent() && request.units() == null)
                || (request.sortOrderPresent() && request.sortOrder() == null)
                || (request.statusPresent() && request.status() == null)) {
            throw new TripValidationException("request", "nullを指定できない項目があります。");
        }
        validateSlotDays(
                tripId,
                request.dayFrom() == null ? current.dayFrom() : request.dayFrom(),
                request.dayTo() == null ? current.dayTo() : request.dayTo());
        if (request.sortOrderPresent()
                && request.sortOrder() >= repository.slotCount(tripId)) {
            throw new TripValidationException(
                    "sortOrder", "sortOrderは既存の枠順の範囲内で指定してください。");
        }
        if (request.statusPresent()
                && request.status() == app.tabikime.kimetabi.trip.SlotStatus.DECIDED) {
            throw new TripValidationException(
                    "status", "DECIDEDへの変更は候補採択APIを使用してください。");
        }
        if (!repository.updateSlot(tripId, slotId, request)) {
            throw new SlotVersionConflictException(
                    repository.findSlot(tripId, slotId).orElseThrow(TripNotFoundException::new));
        }
        return repository.findSlot(tripId, slotId).orElseThrow();
    }

    @Transactional
    public void deleteSlot(String firebaseUid, long tripId, long slotId, long version) {
        authorization.requireSlotResource(
                firebaseUid, tripId, TripPermission.CHANGE_DEADLINE, slotId);
        repository.lockTrip(tripId);
        if (repository.slotHasCandidates(tripId, slotId)) {
            throw new TripValidationException(
                    "slotId", "候補が存在する枠は削除できません。");
        }
        if (!repository.deleteSlot(tripId, slotId, version)) {
            throw new SlotVersionConflictException(
                    repository.findSlot(tripId, slotId).orElseThrow(TripNotFoundException::new));
        }
    }

    @Transactional
    public CandidateResource create(
            String firebaseUid,
            long tripId,
            long slotId,
            UUID idempotencyKey,
            CreateCandidateRequest request
    ) {
        long memberId = authorization.requireSlotResourceMemberId(
                firebaseUid, tripId, TripPermission.CREATE_CANDIDATE, slotId);
        CreateCandidateRequest normalized = normalize(request);
        validate(normalized.title(), normalized.url(), normalized.tags(),
                normalized.estAmount(), normalized.estBasis());
        var idempotencyRequest = new CandidateCreateIdempotencyRequest(
                tripId, slotId, normalized);
        String requestHash = idempotencyStore.hash(idempotencyRequest);
        var replay = idempotencyStore.claimOrReplay(
                firebaseUid, "CREATE_CANDIDATE", idempotencyKey, requestHash);
        if (replay != null) {
            return idempotencyStore.read(replay, CandidateResource.class);
        }
        long candidateId = repository.insert(
                tripId, slotId, memberId, normalized);
        repository.replaceTags(tripId, candidateId, tags(normalized.tags()));
        CandidateResource candidate = repository.find(tripId, candidateId).orElseThrow();
        long revision = eventWriter.nextRevision(tripId);
        eventWriter.write(
                tripId, revision, "CANDIDATE_CREATED", "candidate",
                candidate.id(), candidate.version());
        if (candidate.metadataStatus() == MetadataStatus.PENDING) {
            UUID requestEventId = eventWriter.write(
                    tripId, revision, "CANDIDATE_METADATA_REQUESTED", "candidate",
                    candidate.id(), candidate.version());
            repository.setMetadataRequestEvent(candidate.id(), requestEventId);
        }
        idempotencyStore.complete(
                firebaseUid,
                "CREATE_CANDIDATE",
                idempotencyKey,
                "CANDIDATE",
                candidate.id(),
                candidate);
        return candidate;
    }

    @Transactional
    public CandidateResource update(
            String firebaseUid,
            long tripId,
            long candidateId,
            UpdateCandidateRequest request
    ) {
        authorization.require(firebaseUid, tripId, TripPermission.CREATE_CANDIDATE);
        CandidateResource current = repository.find(tripId, candidateId)
                .orElseThrow(TripNotFoundException::new);
        if (!hasChange(request)) {
            throw new TripValidationException(
                    "request", "version以外に少なくとも1項目を指定してください。");
        }
        if ((request.tagsPresent() && request.tags() == null)
                || (request.statusPresent() && request.status() == null)) {
            throw new TripValidationException("request", "nullを指定できない項目があります。");
        }
        String title = request.titlePresent() ? trimToNull(request.title()) : current.title();
        String url = request.urlPresent() ? normalizeUrl(request.url()) : current.url();
        boolean urlChanged = request.urlPresent() && !Objects.equals(current.url(), url);
        Long amount = request.estAmountPresent() ? request.estAmount() : current.estAmount();
        EstimateBasis basis = request.estBasisPresent() ? request.estBasis() : current.estBasis();
        List<String> updatedTags = request.tagsPresent() ? tags(request.tags()) : current.tags();
        validate(title, url, updatedTags, amount, basis);
        if (request.statusPresent() && request.status() == CandidateStatus.REJECTED) {
            var slot = repository.lockSlot(tripId, current.slotId())
                    .orElseThrow(TripNotFoundException::new);
            if (slot.adoptedCandidateId() != null
                    && slot.adoptedCandidateId() == candidateId) {
                throw new TripValidationException(
                        "status", "採択中の候補は採択を解除してから却下してください。");
            }
        }
        if (request.titlePresent()) request.setTitle(title);
        if (request.urlPresent()) request.setUrl(url);
        if (request.tagsPresent()) request.setTags(updatedTags);
        if (!repository.update(tripId, candidateId, request, urlChanged)) {
            CandidateResource latest = repository.find(tripId, candidateId)
                    .orElseThrow(TripNotFoundException::new);
            throw new CandidateVersionConflictException(latest);
        }
        if (request.tagsPresent()) {
            repository.replaceTags(tripId, candidateId, request.tags());
        }
        CandidateResource candidate = repository.find(tripId, candidateId).orElseThrow();
        long revision = eventWriter.nextRevision(tripId);
        eventWriter.write(
                tripId, revision, "CANDIDATE_UPDATED", "candidate",
                candidate.id(), candidate.version());
        if (urlChanged && candidate.metadataStatus() == MetadataStatus.PENDING) {
            UUID requestEventId = eventWriter.write(
                    tripId, revision, "CANDIDATE_METADATA_REQUESTED", "candidate",
                    candidate.id(), candidate.version());
            repository.setMetadataRequestEvent(candidate.id(), requestEventId);
        }
        return candidate;
    }

    @Transactional
    public CandidateResource retryMetadata(
            String firebaseUid,
            long tripId,
            long candidateId,
            RetryCandidateMetadataRequest request
    ) {
        authorization.require(firebaseUid, tripId, TripPermission.CREATE_CANDIDATE);
        CandidateResource current = repository.find(tripId, candidateId)
                .orElseThrow(TripNotFoundException::new);
        if (current.version() != request.version()) {
            throw new CandidateVersionConflictException(current);
        }
        if (current.url() == null) {
            throw new TripValidationException("candidateId", "URLがある候補だけ再取得できます。");
        }
        if (current.metadataStatus() == MetadataStatus.PENDING
                || current.metadataStatus() == MetadataStatus.PROCESSING) {
            throw new TripValidationException(
                    "metadataStatus", "メタデータはすでに取得中です。");
        }
        if (!repository.requestMetadataRetry(tripId, candidateId, request.version())) {
            CandidateResource latest = repository.find(tripId, candidateId)
                    .orElseThrow(TripNotFoundException::new);
            if (latest.version() != request.version()) {
                throw new CandidateVersionConflictException(latest);
            }
            throw new TripValidationException(
                    "metadataStatus", "現在の状態ではメタデータを再取得できません。");
        }
        CandidateResource candidate = repository.find(tripId, candidateId).orElseThrow();
        long revision = eventWriter.nextRevision(tripId);
        UUID requestEventId = eventWriter.write(
                tripId, revision, "CANDIDATE_METADATA_REQUESTED", "candidate",
                candidate.id(), candidate.version());
        repository.setMetadataRequestEvent(candidate.id(), requestEventId);
        return candidate;
    }

    @Transactional
    public Optional<MetadataWork> startMetadataProcessing(UUID eventId, long candidateId) {
        Objects.requireNonNull(eventId, "eventId");
        CandidateRepository.MetadataCandidate candidate = repository
                .lockMetadataCandidate(candidateId)
                .orElseThrow(TripNotFoundException::new);
        if (!eventId.equals(candidate.requestEventId())
                || candidate.url() == null
                || (candidate.status() != MetadataStatus.PENDING
                    && candidate.status() != MetadataStatus.FAILED_RETRYABLE)) {
            return Optional.empty();
        }
        if (!repository.startMetadataProcessing(candidateId, eventId)) {
            return Optional.empty();
        }
        CandidateResource updated = repository.find(candidate.tripId(), candidateId).orElseThrow();
        long revision = eventWriter.nextRevision(candidate.tripId());
        eventWriter.write(
                candidate.tripId(), revision, "CANDIDATE_UPDATED", "candidate",
                candidateId, updated.version());
        return Optional.of(new MetadataWork(eventId, candidateId, candidate.url()));
    }

    @Transactional
    public CandidateResource completeMetadata(
            UUID eventId,
            long candidateId,
            CandidateMetadataResult result
    ) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(result, "result");
        String title = trimToNull(result.title());
        String imageUrl = trimToNull(result.imageUrl());
        validateMetadataResult(title, imageUrl);
        CandidateRepository.MetadataCandidate candidate = repository
                .lockMetadataCandidate(candidateId)
                .orElseThrow(TripNotFoundException::new);
        if (!eventId.equals(candidate.requestEventId())
                || candidate.status() != MetadataStatus.PROCESSING) {
            return repository.find(candidate.tripId(), candidateId).orElseThrow();
        }
        if (!repository.completeMetadata(candidateId, eventId, title, imageUrl)) {
            return repository.find(candidate.tripId(), candidateId).orElseThrow();
        }
        CandidateResource updated = repository.find(candidate.tripId(), candidateId).orElseThrow();
        long revision = eventWriter.nextRevision(candidate.tripId());
        eventWriter.write(
                candidate.tripId(), revision, "CANDIDATE_METADATA_COMPLETED", "candidate",
                candidateId, updated.version());
        return updated;
    }

    @Transactional
    public CandidateResource failMetadata(
            UUID eventId,
            long candidateId,
            MetadataFailureType failureType,
            String errorCode
    ) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(failureType, "failureType");
        String normalizedCode = trimToNull(errorCode);
        if (normalizedCode == null || !METADATA_ERROR_CODE.matcher(normalizedCode).matches()) {
            throw new IllegalArgumentException("Invalid metadata error code");
        }
        CandidateRepository.MetadataCandidate candidate = repository
                .lockMetadataCandidate(candidateId)
                .orElseThrow(TripNotFoundException::new);
        if (!eventId.equals(candidate.requestEventId())
                || candidate.status() != MetadataStatus.PROCESSING) {
            return repository.find(candidate.tripId(), candidateId).orElseThrow();
        }
        MetadataStatus status = failureType == MetadataFailureType.RETRYABLE
                ? MetadataStatus.FAILED_RETRYABLE
                : MetadataStatus.FAILED_PERMANENT;
        if (!repository.failMetadata(candidateId, eventId, status, normalizedCode)) {
            return repository.find(candidate.tripId(), candidateId).orElseThrow();
        }
        CandidateResource updated = repository.find(candidate.tripId(), candidateId).orElseThrow();
        long revision = eventWriter.nextRevision(candidate.tripId());
        eventWriter.write(
                candidate.tripId(), revision, "CANDIDATE_METADATA_FAILED", "candidate",
                candidateId, updated.version());
        return updated;
    }

    @Transactional
    public VoteView putVote(
            String firebaseUid,
            long tripId,
            long candidateId,
            PutVoteRequest request
    ) {
        long memberId = authorization.requireMemberId(
                firebaseUid, tripId, TripPermission.VOTE);
        repository.find(tripId, candidateId).orElseThrow(TripNotFoundException::new);
        String reason = trimToNull(request.reason());
        if (request.choice() == VoteChoice.NO && reason == null) {
            throw new TripValidationException("reason", "NOには理由を指定してください。");
        }

        boolean updated;
        if (request.version() == null) {
            updated = repository.insertVote(
                    tripId, candidateId, memberId, request.choice(), reason);
        } else {
            updated = repository.updateVote(
                    tripId,
                    candidateId,
                    memberId,
                    request.choice(),
                    reason,
                    request.version());
        }
        if (!updated) {
            VoteResource current = repository.findVote(tripId, candidateId, memberId)
                    .orElseThrow(() -> new TripValidationException(
                            "version", "更新対象の投票がありません。"));
            throw new VoteVersionConflictException(current);
        }
        VoteView view = voteView(tripId, candidateId, memberId);
        long revision = eventWriter.nextRevision(tripId);
        eventWriter.write(
                tripId, revision, "CANDIDATE_VOTE_CHANGED", "candidate",
                candidateId, null);
        return view;
    }

    @Transactional
    public AdoptionResult adopt(
            String firebaseUid,
            long tripId,
            long slotId,
            AdoptCandidateRequest request
    ) {
        authorization.requireSlotResource(
                firebaseUid, tripId, TripPermission.ADOPT_CANDIDATE, slotId);
        var slot = repository.lockSlot(tripId, slotId)
                .orElseThrow(TripNotFoundException::new);
        if (slot.version() != request.version()) {
            throw new SlotVersionConflictException(slot);
        }
        CandidateResource candidate = repository.findForShare(tripId, request.candidateId())
                .orElseThrow(TripNotFoundException::new);
        if (candidate.slotId() != slotId) {
            throw new TripValidationException(
                    "candidateId", "同じ枠に属する候補を指定してください。");
        }
        if (candidate.status() == CandidateStatus.REJECTED) {
            throw new TripValidationException(
                    "candidateId", "却下された候補は採択できません。");
        }
        if (!repository.updateAdoption(
                tripId, slotId, candidate.id(), request.version())) {
            throw new SlotVersionConflictException(
                    repository.findSlot(tripId, slotId).orElseThrow());
        }
        PlanItemResource planItem = repository.upsertPlanItem(
                tripId,
                slotId,
                candidate.id(),
                candidate.title() == null ? "名称未設定" : candidate.title(),
                repository.tripTimezone(tripId),
                candidate.url());
        var updatedSlot = repository.findSlot(tripId, slotId).orElseThrow();
        long revision = eventWriter.nextRevision(tripId);
        eventWriter.write(
                tripId, revision, "SLOT_ADOPTION_CHANGED", "slot",
                slotId, updatedSlot.version());
        return new AdoptionResult(updatedSlot, planItem);
    }

    @Transactional
    public app.tabikime.kimetabi.trip.SlotResource clearAdoption(
            String firebaseUid,
            long tripId,
            long slotId,
            long version
    ) {
        authorization.requireSlotResource(
                firebaseUid, tripId, TripPermission.ADOPT_CANDIDATE, slotId);
        var slot = repository.lockSlot(tripId, slotId)
                .orElseThrow(TripNotFoundException::new);
        if (slot.version() != version) {
            throw new SlotVersionConflictException(slot);
        }
        if (slot.adoptedCandidateId() == null) {
            return slot;
        }
        repository.deletePlanItem(tripId, slotId);
        if (!repository.clearAdoption(tripId, slotId, version)) {
            throw new SlotVersionConflictException(
                    repository.findSlot(tripId, slotId).orElseThrow());
        }
        var updatedSlot = repository.findSlot(tripId, slotId).orElseThrow();
        long revision = eventWriter.nextRevision(tripId);
        eventWriter.write(
                tripId, revision, "SLOT_ADOPTION_CHANGED", "slot",
                slotId, updatedSlot.version());
        return updatedSlot;
    }

    private CreateCandidateRequest normalize(CreateCandidateRequest request) {
        return new CreateCandidateRequest(
                trimToNull(request.title()),
                normalizeUrl(request.url()),
                request.note() == null ? null : request.note().trim(),
                tags(request.tags()),
                request.estAmount(),
                request.estBasis());
    }

    private void validate(
            String title,
            String url,
            List<String> tags,
            Long amount,
            EstimateBasis basis
    ) {
        if (title == null && url == null) {
            throw new TripValidationException("title", "titleまたはurlを指定してください。");
        }
        if ((amount == null) != (basis == null)) {
            throw new TripValidationException(
                    "estAmount", "estAmountとestBasisは同時に指定してください。");
        }
        if (tags.size() > 20) {
            throw new TripValidationException("tags", "タグは20件以下にしてください。");
        }
    }

    private String normalizeUrl(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        try {
            URI uri = URI.create(trimmed);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return trimmed;
        } catch (IllegalArgumentException exception) {
            throw new TripValidationException("url", "httpまたはhttpsのURLを指定してください。");
        }
    }

    private void validateMetadataResult(String title, String imageUrl) {
        if (title != null && title.length() > 200) {
            throw new IllegalArgumentException("Metadata title is too long");
        }
        if (imageUrl != null && imageUrl.length() > 2048) {
            throw new IllegalArgumentException("Metadata image URL is too long");
        }
        if (imageUrl != null) {
            try {
                URI uri = URI.create(imageUrl);
                if (!("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid metadata image URL", exception);
            }
        }
    }

    private List<String> tags(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String tag = trimToNull(value);
            if (tag == null) {
                throw new TripValidationException("tags", "空のタグは指定できません。");
            }
            if (!normalized.add(tag)) {
                throw new TripValidationException("tags", "同じタグは重複して指定できません。");
            }
        }
        return List.copyOf(normalized);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean hasChange(UpdateCandidateRequest request) {
        return request.titlePresent() || request.urlPresent() || request.notePresent()
                || request.tagsPresent() || request.estAmountPresent()
                || request.estBasisPresent() || request.statusPresent();
    }

    private void validateSlotDays(long tripId, int dayFrom, int dayTo) {
        if (dayTo < dayFrom || dayTo > repository.tripDayCount(tripId)) {
            throw new TripValidationException(
                    "dayTo", "枠の日番号は旅行日程の範囲内で指定してください。");
        }
    }

    private boolean hasSlotChange(UpdateSlotRequest request) {
        return request.titlePresent() || request.dayFromPresent()
                || request.dayToPresent() || request.unitsPresent()
                || request.sortOrderPresent() || request.deadlinePresent()
                || request.estPerPersonPresent() || request.statusPresent();
    }

    private VoteView voteView(long tripId, long candidateId, long memberId) {
        VoteVisibility visibility = repository.voteVisibility(tripId);
        List<VoteResource> votes = repository.listActiveVotes(tripId, candidateId);
        VoteResource myVote = votes.stream()
                .filter(vote -> vote.memberId() == memberId)
                .findFirst()
                .orElse(null);
        int yesCount = 0;
        int anyCount = 0;
        int noCount = 0;
        for (VoteResource vote : votes) {
            switch (vote.choice()) {
                case YES -> yesCount++;
                case ANY -> anyCount++;
                case NO -> noCount++;
            }
        }
        return new VoteView(
                visibility,
                yesCount,
                anyCount,
                noCount,
                repository.listUnvotedActiveMemberIds(tripId, candidateId),
                myVote,
                visibility == VoteVisibility.NAMED ? votes : null);
    }

    private CreateSlotRequest withDefaultDeadline(long tripId, CreateSlotRequest request) {
        if (request.deadline() != null) {
            return request;
        }
        CandidateRepository.TripTiming timing = repository.tripTiming(tripId);
        return deadlineCalculator.calculate(
                        request.category(), timing.startsOn(), timing.timezone())
                .map(deadline -> new CreateSlotRequest(
                        request.category(),
                        request.title(),
                        request.dayFrom(),
                        request.dayTo(),
                        request.units(),
                        request.sortOrder(),
                        deadline,
                        request.estPerPerson()))
                .orElse(request);
    }

    private record CandidateCreateIdempotencyRequest(
            long tripId,
            long slotId,
            CreateCandidateRequest candidate
    ) {
    }
}
