package app.tabikime.kimetabi.trip;

import java.util.EnumSet;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service("tripAuthorization")
public class TripAuthorizationService {

    private static final EnumSet<TripPermission> MEMBER_PERMISSIONS = EnumSet.of(
            TripPermission.VIEW_TRIP,
            TripPermission.CREATE_CANDIDATE,
            TripPermission.VOTE,
            TripPermission.ADD_EXPENSE);

    private static final EnumSet<TripPermission> ORGANIZER_PERMISSIONS = union(
            MEMBER_PERMISSIONS,
            TripPermission.UPDATE_TRIP,
            TripPermission.ADOPT_CANDIDATE,
            TripPermission.CHANGE_DEADLINE,
            TripPermission.CORRECT_EXPENSE,
            TripPermission.CREATE_SETTLEMENT,
            TripPermission.CONFIRM_SETTLEMENT);

    private static final EnumSet<TripPermission> OWNER_PERMISSIONS = union(
            ORGANIZER_PERMISSIONS,
            TripPermission.MANAGE_MEMBERS,
            TripPermission.DELETE_TRIP,
            TripPermission.TRANSFER_OWNER);

    private static final Map<MemberRole, EnumSet<TripPermission>> PERMISSIONS_BY_ROLE = Map.of(
            MemberRole.MEMBER, MEMBER_PERMISSIONS,
            MemberRole.ORGANIZER, ORGANIZER_PERMISSIONS,
            MemberRole.OWNER, OWNER_PERMISSIONS);

    private final TripRepository repository;

    public TripAuthorizationService(TripRepository repository) {
        this.repository = repository;
    }

    public TripRepository.StoredMember requireMembership(String firebaseUid, long tripId) {
        return repository.findActiveMember(tripId, firebaseUid)
                .orElseThrow(TripNotFoundException::new);
    }

    public TripRepository.StoredMember require(
            String firebaseUid,
            long tripId,
            TripPermission permission
    ) {
        TripRepository.StoredMember member = permission == TripPermission.VIEW_TRIP
                ? requireMembership(firebaseUid, tripId)
                : repository.lockActiveMemberRole(tripId, firebaseUid)
                        .orElseThrow(TripNotFoundException::new);
        if (!PERMISSIONS_BY_ROLE.get(member.role()).contains(permission)) {
            throw new TripForbiddenException();
        }
        return member;
    }

    public TripRepository.StoredMember requireMemberResource(
            String firebaseUid,
            long tripId,
            TripPermission permission,
            long memberId
    ) {
        TripRepository.StoredMember actor = require(firebaseUid, tripId, permission);
        repository.findMember(tripId, memberId)
                .orElseThrow(TripNotFoundException::new);
        return actor;
    }

    public TripRepository.StoredMember requireSlotResource(
            String firebaseUid,
            long tripId,
            TripPermission permission,
            long slotId
    ) {
        TripRepository.StoredMember actor = require(firebaseUid, tripId, permission);
        if (!repository.slotBelongsToTrip(tripId, slotId)) {
            throw new TripNotFoundException();
        }
        return actor;
    }

    public long requireSlotResourceMemberId(
            String firebaseUid,
            long tripId,
            TripPermission permission,
            long slotId
    ) {
        return requireSlotResource(firebaseUid, tripId, permission, slotId).id();
    }

    private static EnumSet<TripPermission> union(
            EnumSet<TripPermission> base,
            TripPermission... additions
    ) {
        EnumSet<TripPermission> result = EnumSet.copyOf(base);
        result.addAll(java.util.List.of(additions));
        return result;
    }
}
