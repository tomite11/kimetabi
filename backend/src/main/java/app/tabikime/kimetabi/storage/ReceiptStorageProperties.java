package app.tabikime.kimetabi.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kimetabi.receipt-storage")
public record ReceiptStorageProperties(
        String bucket,
        Duration uploadUrlTtl
) {
    public ReceiptStorageProperties {
        uploadUrlTtl = uploadUrlTtl == null ? Duration.ofMinutes(10) : uploadUrlTtl;
    }
}
