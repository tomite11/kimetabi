package app.tabikime.kimetabi.storage;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class ReceiptStorageConfiguration {

    @Bean
    @ConditionalOnMissingBean(Storage.class)
    Storage receiptStorageClient() {
        return StorageOptions.getDefaultInstance().getService();
    }

    @Bean
    @ConditionalOnMissingBean(ReceiptStorageGateway.class)
    ReceiptStorageGateway receiptStorageGateway(
            Storage storage,
            ReceiptStorageProperties properties
    ) {
        if (!StringUtils.hasText(properties.bucket())) {
            return new UnconfiguredReceiptStorageGateway();
        }
        return new GoogleCloudReceiptStorageGateway(storage, properties.bucket());
    }

    private static final class UnconfiguredReceiptStorageGateway
            implements ReceiptStorageGateway {
        @Override
        public UploadCapability createUploadCapability(
                String objectKey, String contentType, long byteSize, java.time.Duration ttl) {
            throw new IllegalStateException("Receipt storage bucket is not configured");
        }

        @Override
        public java.util.Optional<StoredObject> find(String objectKey) {
            throw new IllegalStateException("Receipt storage bucket is not configured");
        }

        @Override
        public void delete(String objectKey) {
            throw new IllegalStateException("Receipt storage bucket is not configured");
        }
    }
}
