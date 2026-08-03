package app.tabikime.kimetabi.candidate;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.tabikime.kimetabi.identity.AppPrincipal;
import app.tabikime.kimetabi.trip.SlotResource;

@Validated
@RestController
@RequestMapping("/api/trips/{tripId}")
public class CandidateController {

    private final CandidateService service;

    public CandidateController(CandidateService service) {
        this.service = service;
    }

    @GetMapping("/slots/{slotId}")
    SlotDetail getSlot(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long slotId
    ) {
        return service.getSlot(principal.firebaseUid(), tripId, slotId);
    }

    @PostMapping("/slots")
    ResponseEntity<SlotResource> createSlot(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @Valid @RequestBody CreateSlotRequest request
    ) {
        SlotResource slot = service.createSlot(principal.firebaseUid(), tripId, request);
        return ResponseEntity.created(
                        URI.create("/api/trips/" + tripId + "/slots/" + slot.id()))
                .body(slot);
    }

    @PutMapping("/slots/order")
    java.util.List<SlotResource> reorderSlots(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @Valid @RequestBody ReorderSlotsRequest request
    ) {
        return service.reorderSlots(principal.firebaseUid(), tripId, request);
    }

    @PatchMapping("/slots/{slotId}")
    SlotResource updateSlot(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long slotId,
            @Valid @RequestBody UpdateSlotRequest request
    ) {
        return service.updateSlot(principal.firebaseUid(), tripId, slotId, request);
    }

    @DeleteMapping("/slots/{slotId}")
    ResponseEntity<Void> deleteSlot(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long slotId,
            @RequestParam @Min(0) long version
    ) {
        service.deleteSlot(principal.firebaseUid(), tripId, slotId, version);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/slots/{slotId}/split")
    java.util.List<SlotResource> splitSlot(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long slotId,
            @Valid @RequestBody SplitSlotRequest request
    ) {
        return service.splitSlot(principal.firebaseUid(), tripId, slotId, request);
    }

    @PostMapping("/slots/{slotId}/candidates")
    ResponseEntity<CandidateResource> createCandidate(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long slotId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CreateCandidateRequest request
    ) {
        CandidateResource candidate = service.create(
                principal.firebaseUid(), tripId, slotId, idempotencyKey, request);
        return ResponseEntity.created(URI.create(
                        "/api/trips/" + tripId + "/candidates/" + candidate.id()))
                .body(candidate);
    }

    @PatchMapping("/candidates/{candidateId}")
    CandidateResource updateCandidate(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long candidateId,
            @Valid @RequestBody UpdateCandidateRequest request
    ) {
        return service.update(principal.firebaseUid(), tripId, candidateId, request);
    }

    @PostMapping("/candidates/{candidateId}/metadata/retry")
    CandidateResource retryCandidateMetadata(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long candidateId,
            @Valid @RequestBody RetryCandidateMetadataRequest request
    ) {
        return service.retryMetadata(
                principal.firebaseUid(), tripId, candidateId, request);
    }

    @PutMapping("/candidates/{candidateId}/vote")
    VoteView putVote(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long candidateId,
            @Valid @RequestBody PutVoteRequest request
    ) {
        return service.putVote(principal.firebaseUid(), tripId, candidateId, request);
    }

    @PutMapping("/slots/{slotId}/adoption")
    AdoptionResult adoptCandidate(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long slotId,
            @Valid @RequestBody AdoptCandidateRequest request
    ) {
        return service.adopt(
                principal.firebaseUid(), tripId, slotId, request);
    }

    @DeleteMapping("/slots/{slotId}/adoption")
    SlotResource clearAdoption(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long slotId,
            @RequestParam @Min(0) long version
    ) {
        return service.clearAdoption(principal.firebaseUid(), tripId, slotId, version);
    }
}
