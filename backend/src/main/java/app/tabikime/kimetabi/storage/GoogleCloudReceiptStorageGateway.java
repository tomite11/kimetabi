package app.tabikime.kimetabi.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;

final class GoogleCloudReceiptStorageGateway implements ReceiptStorageGateway {

    private final Storage storage;
    private final String bucket;

    GoogleCloudReceiptStorageGateway(Storage storage, String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public UploadCapability createUploadCapability(
            String objectKey,
            String contentType,
            long byteSize,
            Duration ttl
    ) {
        Map<String, String> headers = Map.of(
                "Content-Type", contentType,
                "Content-Length", Long.toString(byteSize),
                "x-goog-if-generation-match", "0");
        BlobInfo blobInfo = BlobInfo.newBuilder(bucket, objectKey)
                .setContentType(contentType)
                .build();
        var signedUrl = storage.signUrl(
                blobInfo,
                ttl.toSeconds(),
                TimeUnit.SECONDS,
                Storage.SignUrlOption.httpMethod(com.google.cloud.storage.HttpMethod.PUT),
                Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.withExtHeaders(headers));
        return new UploadCapability(URI.create(signedUrl.toString()), headers);
    }

    @Override
    public Optional<StoredObject> find(String objectKey) {
        Blob blob = storage.get(bucket, objectKey, Storage.BlobGetOption.fields(
                Storage.BlobField.CONTENT_TYPE,
                Storage.BlobField.SIZE));
        if (blob == null || !blob.exists()) return Optional.empty();
        return Optional.of(new StoredObject(blob.getContentType(), blob.getSize()));
    }
}
