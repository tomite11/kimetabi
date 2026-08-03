package app.tabikime.kimetabi.expense;

import java.util.UUID;

public record ExpenseReceiptResource(
        UUID id,
        String contentType,
        long byteSize,
        String status
) {
}
