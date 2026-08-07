package app.tabikime.kimetabi.expense;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.tabikime.kimetabi.identity.AppPrincipal;

@Validated
@RestController
@RequestMapping("/api/trips/{tripId}/expenses/{expenseId}")
class ExpenseReceiptUploadController {

    private final ExpenseReceiptUploadService service;

    ExpenseReceiptUploadController(ExpenseReceiptUploadService service) {
        this.service = service;
    }

    @PostMapping("/receipt-upload")
    ResponseEntity<ReceiptUploadResource> prepare(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long expenseId,
            @Valid @RequestBody PrepareReceiptUploadRequest request
    ) {
        return ResponseEntity.status(201).body(
                service.prepare(principal.firebaseUid(), tripId, expenseId, request));
    }

    @PostMapping("/receipts/{receiptId}/completion")
    ExpenseResource complete(
            @AuthenticationPrincipal AppPrincipal principal,
            @PathVariable @Min(1) long tripId,
            @PathVariable @Min(1) long expenseId,
            @PathVariable UUID receiptId,
            @Valid @RequestBody CompleteReceiptUploadRequest request
    ) {
        return service.complete(
                principal.firebaseUid(), tripId, expenseId, receiptId, request);
    }
}
