package app.tabikime.kimetabi.expense;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.tabikime.kimetabi.identity.AppPrincipal;

@Validated
@RestController
@RequestMapping("/api/trips/{tripId}/expenses")
public class ExpenseController {

    private final ExpenseService service;

    public ExpenseController(ExpenseService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ExpenseResource> createDraft(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CreateExpenseDraftRequest request
    ) {
        ExpenseResource expense = service.create(principal.firebaseUid(), tripId, request);
        return ResponseEntity.created(
                        URI.create("/api/trips/" + tripId + "/expenses/" + expense.id()))
                .body(expense);
    }

    @GetMapping("/{expenseId}")
    ExpenseResource get(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long expenseId
    ) {
        return service.get(principal.firebaseUid(), tripId, expenseId);
    }

    @GetMapping
    ExpensePage list(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @RequestParam(required = false) @Size(max = 500) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) ExpenseStatus status
    ) {
        return service.list(principal.firebaseUid(), tripId, cursor, limit, status);
    }

    @GetMapping("/share-preset")
    ResponseEntity<ExpenseSharePresetResource> getPreviousSharePreset(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId
    ) {
        return service.previousSharePreset(principal.firebaseUid(), tripId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PatchMapping("/{expenseId}")
    ExpenseResource update(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long expenseId,
            @Valid @RequestBody UpdateExpenseRequest request
    ) {
        return service.update(principal.firebaseUid(), tripId, expenseId, request);
    }

    @DeleteMapping("/{expenseId}")
    ResponseEntity<Void> deleteDraft(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long expenseId,
            @RequestParam @Min(0) long version
    ) {
        service.deleteDraft(principal.firebaseUid(), tripId, expenseId, version);
        return ResponseEntity.noContent().build();
    }
}
