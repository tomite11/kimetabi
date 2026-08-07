package app.tabikime.kimetabi.expense;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

record ReceiptUploadResource(
        UUID receiptId,
        URI uploadUrl,
        OffsetDateTime expiresAt,
        Map<String, String> requiredHeaders
) {
    ReceiptUploadResource {
        requiredHeaders = Map.copyOf(requiredHeaders);
    }
}
