package app.tabikime.kimetabi.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kimetabi.receipt-storage")
public record ReceiptStorageProperties(
        String bucket,
        Duration uploadUrlTtl,
        Duration orphanRetention,
        int orphanCleanupBatchSize
) {
    public ReceiptStorageProperties {
        uploadUrlTtl = uploadUrlTtl == null ? Duration.ofMinutes(10) : uploadUrlTtl;
        orphanRetention = orphanRetention == null ? Duration.ofHours(24) : orphanRetention;
        if (orphanRetention.isZero() || orphanRetention.isNegative()) {
            throw new IllegalArgumentException("orphanRetention must be positive");
        }
        orphanCleanupBatchSize = orphanCleanupBatchSize <= 0
                ? 100
                : Math.min(orphanCleanupBatchSize, 100);
    }
}
