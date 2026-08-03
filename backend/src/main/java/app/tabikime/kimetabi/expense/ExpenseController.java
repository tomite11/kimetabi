package app.tabikime.kimetabi.expense;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
