package app.tabikime.kimetabi.trip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
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

    public TripService(TripRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
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
                List.of());
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

    private TripResource toResource(TripRepository.StoredTrip trip) {
        TripPhase phase = trip.phaseOverride();
        if (phase == null) {
            LocalDate today = LocalDate.now(ZoneId.of(trip.timezone()));
            if (today.isBefore(trip.startsOn())) {
                phase = TripPhase.PLANNING;
            } else if (today.isAfter(trip.endsOn())) {
                phase = TripPhase.SETTLING;
            } else {
                phase = TripPhase.TRAVELING;
            }
        }
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
