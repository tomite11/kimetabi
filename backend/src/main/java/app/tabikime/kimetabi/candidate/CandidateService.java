package app.tabikime.kimetabi.candidate;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.tabikime.kimetabi.trip.TripAuthorizationService;
import app.tabikime.kimetabi.trip.TripNotFoundException;
import app.tabikime.kimetabi.trip.TripPermission;
import app.tabikime.kimetabi.trip.TripValidationException;

@Service
public class CandidateService {

    private final CandidateRepository repository;
    private final TripAuthorizationService authorization;

    CandidateService(
            CandidateRepository repository,
            TripAuthorizationService authorization
    ) {
        this.repository = repository;
        this.authorization = authorization;
    }

    @Transactional(readOnly = true)
    public SlotDetail getSlot(String firebaseUid, long tripId, long slotId) {
        authorization.requireSlotResource(
                firebaseUid, tripId, TripPermission.VIEW_TRIP, slotId);
        return new SlotDetail(
                repository.findSlot(tripId, slotId).orElseThrow(TripNotFoundException::new),
                repository.list(tripId, slotId),
                Map.of());
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
        long slotId = repository.insertSlot(tripId, request);
        return repository.findSlot(tripId, slotId).orElseThrow();
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
            CreateCandidateRequest request
    ) {
        long memberId = authorization.requireSlotResourceMemberId(
                firebaseUid, tripId, TripPermission.CREATE_CANDIDATE, slotId);
        CreateCandidateRequest normalized = normalize(request);
        validate(normalized.title(), normalized.url(), normalized.tags(),
                normalized.estAmount(), normalized.estBasis());
        long candidateId = repository.insert(
                tripId, slotId, memberId, normalized);
        repository.replaceTags(tripId, candidateId, tags(normalized.tags()));
        return repository.find(tripId, candidateId).orElseThrow();
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
        Long amount = request.estAmountPresent() ? request.estAmount() : current.estAmount();
        EstimateBasis basis = request.estBasisPresent() ? request.estBasis() : current.estBasis();
        List<String> updatedTags = request.tagsPresent() ? tags(request.tags()) : current.tags();
        validate(title, url, updatedTags, amount, basis);
        if (request.titlePresent()) request.setTitle(title);
        if (request.urlPresent()) request.setUrl(url);
        if (request.tagsPresent()) request.setTags(updatedTags);
        if (!repository.update(tripId, candidateId, request)) {
            CandidateResource latest = repository.find(tripId, candidateId)
                    .orElseThrow(TripNotFoundException::new);
            throw new CandidateVersionConflictException(latest);
        }
        if (request.tagsPresent()) {
            repository.replaceTags(tripId, candidateId, request.tags());
        }
        return repository.find(tripId, candidateId).orElseThrow();
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
}
