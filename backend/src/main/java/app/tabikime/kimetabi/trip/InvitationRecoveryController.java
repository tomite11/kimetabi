package app.tabikime.kimetabi.trip;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.tabikime.kimetabi.identity.AppPrincipal;

@Validated
@RestController
@RequestMapping("/api")
public class InvitationRecoveryController {

    private final InvitationRecoveryService service;
    private final ClientAddressResolver clientAddressResolver;

    public InvitationRecoveryController(
            InvitationRecoveryService service,
            ClientAddressResolver clientAddressResolver
    ) {
        this.service = service;
        this.clientAddressResolver = clientAddressResolver;
    }

    @PostMapping("/trips/{tripId}/invitations")
    ResponseEntity<InvitationLink> createInvitation(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId
    ) {
        InvitationLink link = service.createInvitation(principal.firebaseUid(), tripId);
        return ResponseEntity
                .created(URI.create("/api/trips/" + tripId + "/invitations/" + link.id()))
                .body(link);
    }

    @DeleteMapping("/trips/{tripId}/invitations/{invitationId}")
    ResponseEntity<Void> revokeInvitation(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long invitationId
    ) {
        service.revokeInvitation(principal.firebaseUid(), tripId, invitationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invitations/accept")
    ResponseEntity<TripSnapshot> acceptInvitation(
            @AuthenticationPrincipal AppPrincipal principal,
            @Valid @RequestBody AcceptInvitationRequest request,
            HttpServletRequest httpRequest
    ) {
        InvitationRecoveryService.InvitationAcceptance acceptance =
                service.acceptInvitation(
                        principal.firebaseUid(),
                        request,
                        clientAddressResolver.resolve(httpRequest));
        return ResponseEntity.status(acceptance.created() ? 201 : 200)
                .body(acceptance.snapshot());
    }

    @PostMapping("/trips/{tripId}/members/{memberId}/recovery-links")
    ResponseEntity<RecoveryLink> createRecovery(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long memberId
    ) {
        RecoveryLink link = service.createRecovery(
                principal.firebaseUid(), tripId, memberId);
        return ResponseEntity
                .created(URI.create("/api/trips/" + tripId
                        + "/members/" + memberId + "/recovery-links/" + link.id()))
                .body(link);
    }

    @PostMapping("/recoveries/accept")
    MemberResource acceptRecovery(
            @AuthenticationPrincipal AppPrincipal principal,
            @Valid @RequestBody AcceptRecoveryRequest request,
            HttpServletRequest httpRequest
    ) {
        return service.acceptRecovery(
                principal.firebaseUid(),
                request,
                clientAddressResolver.resolve(httpRequest));
    }
}
