package app.tabikime.kimetabi.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public interface ReceiptStorageGateway {

    UploadCapability createUploadCapability(
            String objectKey,
            String contentType,
            long byteSize,
            Duration ttl
    );

    Optional<StoredObject> find(String objectKey);

    record UploadCapability(URI url, Map<String, String> requiredHeaders) {
        public UploadCapability {
            requiredHeaders = Map.copyOf(requiredHeaders);
        }
    }

    record StoredObject(String contentType, long byteSize) {
    }
}
