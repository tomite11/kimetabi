package app.tabikime.kimetabi.trip;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationRecoveryService {

    private static final Duration INVITATION_LIFETIME = Duration.ofDays(7);
    private static final Duration RECOVERY_LIFETIME = Duration.ofHours(24);

    private final AccessTokenRepository tokenRepository;
    private final TripRepository tripRepository;
    private final TripAuthorizationService authorization;
    private final SensitiveTokenCodec tokenCodec;
    private final TokenRateLimitService rateLimitService;
    private final TripService tripService;
    private final Clock clock;

    public InvitationRecoveryService(
            AccessTokenRepository tokenRepository,
            TripRepository tripRepository,
            TripAuthorizationService authorization,
            SensitiveTokenCodec tokenCodec,
            TokenRateLimitService rateLimitService,
            TripService tripService,
            Clock clock
    ) {
        this.tokenRepository = tokenRepository;
        this.tripRepository = tripRepository;
        this.authorization = authorization;
        this.tokenCodec = tokenCodec;
        this.rateLimitService = rateLimitService;
        this.tripService = tripService;
        this.clock = clock;
    }

    @Transactional
    public InvitationLink createInvitation(String firebaseUid, long tripId) {
        TripRepository.StoredMember creator = authorization.require(
                firebaseUid,
                tripId,
                TripPermission.MANAGE_MEMBERS);
        String token = tokenCodec.generate();
        Instant expiresAt = clock.instant().plus(INVITATION_LIFETIME);
        long invitationId = tokenRepository.insertInvitation(
                tripId,
                creator.id(),
                tokenCodec.hash(token),
                expiresAt);
        return new InvitationLink(
                invitationId,
                URI.create("/join/" + token),
                expiresAt);
    }

    @Transactional
    public void revokeInvitation(String firebaseUid, long tripId, long invitationId) {
        authorization.require(firebaseUid, tripId, TripPermission.MANAGE_MEMBERS);
        if (!tokenRepository.invitationBelongsToTrip(tripId, invitationId)
                || !tokenRepository.revokeInvitation(tripId, invitationId)) {
            throw new TripNotFoundException();
        }
    }

    @Transactional
    public InvitationAcceptance acceptInvitation(
            String firebaseUid,
            AcceptInvitationRequest request,
            String clientAddress
    ) {
        String tokenHash = tokenCodec.hash(request.token());
        enforceRateLimit(clientAddress, tokenHash);
        AccessTokenRepository.StoredInvitation invitation =
                tokenRepository.lockInvitation(tokenHash)
                        .filter(this::isUsable)
                        .orElseThrow(InvalidAccessTokenException::new);

        tripRepository.lockUidAssignment(invitation.tripId(), firebaseUid);
        TripRepository.StoredMembership existing =
                tripRepository.findMembership(invitation.tripId(), firebaseUid).orElse(null);
        boolean created = existing == null
                && tripRepository.insertGuestMember(
                        invitation.tripId(),
                        firebaseUid,
                        request.name().trim());
        if (!created && existing == null) {
            existing = tripRepository.findMembership(invitation.tripId(), firebaseUid)
                    .orElseThrow(RecoveryConflictException::new);
        }
        if (!created && existing.status() != MemberStatus.ACTIVE) {
            tripRepository.restoreMember(
                    invitation.tripId(),
                    existing.id(),
                    request.name().trim());
        }
        if (!tokenRepository.consumeInvitation(invitation.id())) {
            throw new InvalidAccessTokenException();
        }
        tripRepository.touchTrip(invitation.tripId());
        return new InvitationAcceptance(
                created,
                tripService.snapshot(firebaseUid, invitation.tripId()));
    }

    @Transactional
    public RecoveryLink createRecovery(
            String firebaseUid,
            long tripId,
            long memberId
    ) {
        TripRepository.StoredMember creator = authorization.requireMemberResource(
                firebaseUid,
                tripId,
                TripPermission.MANAGE_MEMBERS,
                memberId);
        String token = tokenCodec.generate();
        Instant expiresAt = clock.instant().plus(RECOVERY_LIFETIME);
        long recoveryId = tokenRepository.insertRecovery(
                tripId,
                memberId,
                creator.id(),
                tokenCodec.hash(token),
                expiresAt);
        return new RecoveryLink(
                recoveryId,
                URI.create("/recover/" + token),
                expiresAt);
    }

    @Transactional
    public MemberResource acceptRecovery(
            String firebaseUid,
            AcceptRecoveryRequest request,
            String clientAddress
    ) {
        String tokenHash = tokenCodec.hash(request.token());
        enforceRateLimit(clientAddress, tokenHash);
        AccessTokenRepository.StoredRecovery recovery =
                tokenRepository.lockRecovery(tokenHash)
                        .filter(this::isUsable)
                        .orElseThrow(InvalidAccessTokenException::new);
        tripRepository.lockUidAssignment(recovery.tripId(), firebaseUid);
        if (!tripRepository.replaceMemberUid(
                recovery.tripId(), recovery.memberId(), firebaseUid)) {
            throw new RecoveryConflictException();
        }
        if (!tokenRepository.consumeRecovery(recovery.id())) {
            throw new InvalidAccessTokenException();
        }
        tripRepository.touchTrip(recovery.tripId());
        return tripRepository.getMemberResource(recovery.tripId(), recovery.memberId());
    }

    private void enforceRateLimit(String clientAddress, String tokenHash) {
        if (!rateLimitService.allow(clientAddress, tokenHash)) {
            throw new TokenRateLimitExceededException();
        }
    }

    private boolean isUsable(AccessTokenRepository.StoredInvitation invitation) {
        return invitation.usedAt() == null
                && invitation.revokedAt() == null
                && invitation.expiresAt().isAfter(clock.instant());
    }

    private boolean isUsable(AccessTokenRepository.StoredRecovery recovery) {
        return recovery.usedAt() == null
                && recovery.revokedAt() == null
                && recovery.expiresAt().isAfter(clock.instant());
    }

    public record InvitationAcceptance(boolean created, TripSnapshot snapshot) {
    }
}
