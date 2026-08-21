package app.tabikime.kimetabi.settlement;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.tabikime.kimetabi.identity.AppPrincipal;

@Validated
@RestController
@RequestMapping("/api/trips/{tripId}/settlements")
class SettlementController {

    private final SettlementService service;

    SettlementController(SettlementService service) {
        this.service = service;
    }

    @GetMapping
    SettlementPage list(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Positive long tripId,
            @RequestParam(required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return service.list(principal.firebaseUid(), tripId, cursor, pageSize);
    }

    @PostMapping
    ResponseEntity<SettlementResource> create(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Positive long tripId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CreateSettlementRequest request
    ) {
        SettlementResource result = service.createDraft(
                principal.firebaseUid(), tripId, idempotencyKey, request);
        return ResponseEntity.created(URI.create(
                "/api/trips/" + tripId + "/settlements/" + result.id())).body(result);
    }

    @GetMapping("/{settlementId}")
    SettlementResource get(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Positive long tripId,
            @PathVariable @Positive long settlementId
    ) {
        return service.get(principal.firebaseUid(), tripId, settlementId);
    }

    @PostMapping("/{settlementId}/confirmation")
    SettlementResource confirm(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Positive long tripId,
            @PathVariable @Positive long settlementId,
            @Valid @RequestBody ConfirmSettlementRequest request
    ) {
        return service.confirm(principal.firebaseUid(), tripId, settlementId, request.version());
    }

    @PatchMapping("/{settlementId}/transfers/{transferId}")
    SettlementResource updateTransfer(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Positive long tripId,
            @PathVariable @Positive long settlementId,
            @PathVariable @Positive long transferId,
            @Valid @RequestBody UpdateTransferRequest request
    ) {
        return service.updateTransfer(
                principal.firebaseUid(), tripId, settlementId, transferId, request);
    }
}
