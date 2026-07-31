package app.tabikime.kimetabi.trip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TripService {

    private final TripRepository repository;
    private final ObjectMapper objectMapper;
    private final InitialSlotFactory initialSlotFactory;
    private final TripPhasePolicy phasePolicy;

    public TripService(
            TripRepository repository,
            ObjectMapper objectMapper,
            InitialSlotFactory initialSlotFactory,
            TripPhasePolicy phasePolicy
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.initialSlotFactory = initialSlotFactory;
        this.phasePolicy = phasePolicy;
    }

    @Transactional
    public TripSnapshot create(
            String firebaseUid,
            UUID idempotencyKey,
            CreateTripRequest request
    ) {
        validate(request);
        String requestHash = requestHash(request);
        if (!repository.claimIdempotencyKey(firebaseUid, idempotencyKey, requestHash)) {
            TripRepository.IdempotencyRecord existing =
                    repository.getIdempotencyRecord(firebaseUid, idempotencyKey);
            if (!existing.requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException();
            }
            if (existing.resourceId() == null) {
                throw new IllegalStateException("Incomplete idempotency record");
            }
            return readSnapshot(existing.responseBody());
        }

        long tripId = repository.insertTrip(request);
        long ownerId = repository.insertOwner(tripId, firebaseUid, request.ownerName());
        repository.setOwner(tripId, ownerId);
        repository.insertInitialSlots(
                tripId,
                initialSlotFactory.create(
                        request.startsOn(),
                        request.endsOn(),
                        request.timezone().trim()));
        TripSnapshot snapshot = snapshot(firebaseUid, tripId);
        repository.completeIdempotencyKey(
                firebaseUid,
                idempotencyKey,
                tripId,
                writeSnapshot(snapshot));
        return snapshot;
    }

    @Transactional(readOnly = true)
    public TripPage list(String firebaseUid, String cursor, int limit) {
        TripCursor.Decoded decoded = cursor == null ? null : TripCursor.decode(cursor);
        List<TripRepository.StoredTrip> rows = repository.listActiveMemberTrips(
                firebaseUid,
                decoded == null ? null : decoded.updatedAt(),
                decoded == null ? null : decoded.id(),
                limit + 1);
        boolean hasNext = rows.size() > limit;
        List<TripRepository.StoredTrip> pageRows =
                hasNext ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasNext) {
            TripRepository.StoredTrip last = pageRows.get(pageRows.size() - 1);
            nextCursor = TripCursor.encode(last.updatedAt(), last.id());
        }
        return new TripPage(pageRows.stream().map(this::toResource).toList(), nextCursor);
    }

    @Transactional(readOnly = true)
    public TripSnapshot snapshot(String firebaseUid, long tripId) {
        TripRepository.StoredTrip trip = repository.findActiveMemberTrip(tripId, firebaseUid)
                .orElseThrow(TripNotFoundException::new);
        return new TripSnapshot(
                toResource(trip),
                repository.listMembers(tripId),
                repository.listSlots(tripId));
    }

    @Transactional
    public TripResource update(
            String firebaseUid,
            long tripId,
            UpdateTripRequest request
    ) {
        if (!request.hasChange()) {
            throw new TripValidationException(
                    "request",
                    "version以外に少なくとも1項目を指定してください。");
        }
        TripRepository.StoredTrip current = repository.findActiveMemberTrip(tripId, firebaseUid)
                .orElseThrow(TripNotFoundException::new);
        MemberRole role = repository.findActiveMemberRole(tripId, firebaseUid)
                .orElseThrow(TripNotFoundException::new);
        if (role == MemberRole.MEMBER) {
            throw new TripForbiddenException();
        }
        validateUpdate(current, request);
        if (!repository.updateTrip(tripId, firebaseUid, request.version(), request)) {
            TripRepository.StoredTrip latest =
                    repository.findActiveMemberTrip(tripId, firebaseUid)
                            .orElseThrow(TripNotFoundException::new);
            throw new TripVersionConflictException(toResource(latest));
        }
        return repository.findActiveMemberTrip(tripId, firebaseUid)
                .map(this::toResource)
                .orElseThrow(TripNotFoundException::new);
    }

    private void validate(CreateTripRequest request) {
        if (request.endsOn().isBefore(request.startsOn())) {
            throw new TripValidationException(
                    "endsOn",
                    "帰着日は出発日以降を指定してください。");
        }
        try {
            ZoneId.of(request.timezone().trim());
        } catch (ZoneRulesException exception) {
            throw new TripValidationException(
                    "timezone",
                    "有効なIANAタイムゾーンを指定してください。");
        }
    }

    private void validateUpdate(
            TripRepository.StoredTrip current,
            UpdateTripRequest request
    ) {
        java.time.LocalDate startsOn =
                request.startsOn() == null ? current.startsOn() : request.startsOn();
        java.time.LocalDate endsOn =
                request.endsOn() == null ? current.endsOn() : request.endsOn();
        if (endsOn.isBefore(startsOn)) {
            throw new TripValidationException(
                    "endsOn",
                    "帰着日は出発日以降を指定してください。");
        }
        String timezone =
                request.timezone() == null ? current.timezone() : request.timezone().trim();
        if (request.title() != null && request.title().isBlank()) {
            throw new TripValidationException("title", "旅行名を入力してください。");
        }
        if (request.destination() != null && request.destination().isBlank()) {
            throw new TripValidationException("destination", "目的地を入力してください。");
        }
        try {
            ZoneId.of(timezone);
        } catch (ZoneRulesException exception) {
            throw new TripValidationException(
                    "timezone",
                    "有効なIANAタイムゾーンを指定してください。");
        }
    }

    private TripResource toResource(TripRepository.StoredTrip trip) {
        TripPhase phase = phasePolicy.determine(
                trip.startsOn(),
                trip.endsOn(),
                trip.timezone(),
                trip.phaseOverride());
        return new TripResource(
                trip.id(),
                trip.title(),
                trip.destination(),
                trip.startsOn(),
                trip.endsOn(),
                trip.timezone(),
                trip.expectedMemberCount(),
                phase,
                trip.phaseOverride(),
                trip.voteVisibility(),
                trip.budgetCap(),
                trip.revision(),
                trip.version());
    }

    private String requestHash(CreateTripRequest request) {
        String canonical = String.join(
                "\u001f",
                request.title().trim(),
                request.destination().trim(),
                request.startsOn().toString(),
                request.endsOn().toString(),
                request.timezone().trim(),
                Integer.toString(request.expectedMemberCount()),
                request.ownerName().trim(),
                request.budgetCap() == null ? "" : request.budgetCap().toString(),
                request.voteVisibility() == null ? VoteVisibility.NAMED.name()
                        : request.voteVisibility().name());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String writeSnapshot(TripSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not store idempotent response", exception);
        }
    }

    private TripSnapshot readSnapshot(String responseBody) {
        if (responseBody == null) {
            throw new IllegalStateException("Idempotency response is incomplete");
        }
        try {
            return objectMapper.readValue(responseBody, TripSnapshot.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not read idempotent response", exception);
        }
    }
}
