package app.tabikime.kimetabi.ingestion.url;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.zip.ZipException;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import app.tabikime.kimetabi.ingestion.url.PinnedHttpTransport.Response;
import app.tabikime.kimetabi.ingestion.url.UrlFetchException.Reason;

public final class SafeUrlFetcher {

    private static final int BUFFER_SIZE = 8192;
    private static final ExecutorService DNS_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();
    private final UrlAddressValidator validator;
    private final PinnedHttpTransport transport;
    private final UrlFetchProperties properties;
    private final LongSupplier nanoTime;
    private final HostConcurrencyLimiter concurrencyLimiter;

    public SafeUrlFetcher(
            UrlAddressValidator validator,
            PinnedHttpTransport transport,
            UrlFetchProperties properties
    ) {
        this(validator, transport, properties, System::nanoTime,
                new HostConcurrencyLimiter(properties.maxConcurrentPerHost()));
    }

    SafeUrlFetcher(
            UrlAddressValidator validator,
            PinnedHttpTransport transport,
            UrlFetchProperties properties,
            LongSupplier nanoTime
    ) {
        this(validator, transport, properties, nanoTime,
                new HostConcurrencyLimiter(properties.maxConcurrentPerHost()));
    }

    SafeUrlFetcher(
            UrlAddressValidator validator,
            PinnedHttpTransport transport,
            UrlFetchProperties properties,
            LongSupplier nanoTime,
            HostConcurrencyLimiter concurrencyLimiter
    ) {
        this.validator = validator;
        this.transport = transport;
        this.properties = properties;
        this.nanoTime = nanoTime;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    public FetchResult fetch(URI initialUrl) throws UrlFetchException {
        Objects.requireNonNull(initialUrl, "initialUrl");
        long deadline = nanoTime.getAsLong() + properties.totalTimeout().toNanos();
        URI current = initialUrl;

        for (int redirectCount = 0; ; redirectCount++) {
            ensureBeforeDeadline(deadline);
            ValidatedUrl target = validateBeforeDeadline(current, deadline);
            Duration connectTimeout = remainingConnectTimeout(deadline);
            Duration operationTimeout = remainingOperationTimeout(deadline);

            try (HostConcurrencyLimiter.Lease ignored = concurrencyLimiter.acquire(
                    target.hostname(), remainingOperationTimeout(deadline));
                    Response response = transport.execute(
                            target,
                            target.addresses().getFirst(),
                            connectTimeout,
                            operationTimeout)) {
                ensureBeforeDeadline(deadline);
                if (isRedirect(response.statusCode())) {
                    if (redirectCount >= properties.maxRedirects()) {
                        throw new UrlFetchException(
                                Reason.TOO_MANY_REDIRECTS,
                                "Redirect limit exceeded");
                    }
                    String location = response.firstHeader("Location");
                    if (location == null || location.isBlank()) {
                        throw new UrlFetchException(
                                Reason.INVALID_URL,
                                "Redirect response has no Location");
                    }
                    current = target.uri().resolve(location);
                    continue;
                }

                rejectOversizedContentLength(response);
                byte[] body = readBounded(response.decodedBody(), deadline);
                return new FetchResult(
                        target.uri(), response.statusCode(), response.headers(), body);
            } catch (UrlFetchException exception) {
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new UrlFetchException(
                        Reason.TRANSPORT_FAILURE,
                        "Interrupted while waiting for metadata host capacity",
                        exception);
            } catch (SocketTimeoutException exception) {
                throw new UrlFetchException(
                        Reason.TIMEOUT,
                        "URL fetch deadline exceeded",
                        exception);
            } catch (SSLPeerUnverifiedException | SSLHandshakeException exception) {
                throw new UrlFetchException(
                        Reason.TLS_VALIDATION_FAILURE,
                        "URL TLS identity validation failed",
                        exception);
            } catch (InvalidHttpResponseException exception) {
                throw new UrlFetchException(
                        Reason.INVALID_RESPONSE,
                        "URL server returned an invalid HTTP response",
                        exception);
            } catch (ZipException exception) {
                throw new UrlFetchException(
                        Reason.INVALID_RESPONSE,
                        "URL server returned an invalid compressed response",
                        exception);
            } catch (IOException exception) {
                throw new UrlFetchException(
                        Reason.TRANSPORT_FAILURE,
                        "URL transport failed",
                        exception);
            }
        }
    }

    private ValidatedUrl validateBeforeDeadline(URI uri, long deadline)
            throws UrlFetchException {
        long remaining = remainingNanos(deadline);
        Future<ValidatedUrl> validation = DNS_EXECUTOR.submit(() -> validator.validate(uri));
        try {
            return validation.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            validation.cancel(true);
            throw timeout();
        } catch (InterruptedException exception) {
            validation.cancel(true);
            Thread.currentThread().interrupt();
            throw new UrlFetchException(
                    Reason.TRANSPORT_FAILURE, "URL validation was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof UrlFetchException urlFetchException) {
                throw urlFetchException;
            }
            throw new UrlFetchException(
                    Reason.TRANSPORT_FAILURE, "URL validation failed", exception.getCause());
        }
    }

    private Duration remainingConnectTimeout(long deadline) throws UrlFetchException {
        long remaining = remainingNanos(deadline);
        return Duration.ofNanos(Math.min(remaining, properties.connectTimeout().toNanos()));
    }

    private Duration remainingOperationTimeout(long deadline) throws UrlFetchException {
        return Duration.ofNanos(remainingNanos(deadline));
    }

    private long remainingNanos(long deadline) throws UrlFetchException {
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0) {
            throw timeout();
        }
        return remaining;
    }

    private void rejectOversizedContentLength(Response response) throws UrlFetchException {
        String rawLength = response.firstHeader("Content-Length");
        if (rawLength == null) {
            return;
        }
        try {
            if (Long.parseLong(rawLength) > properties.maxBodyBytes()) {
                throw tooLarge();
            }
        } catch (NumberFormatException ignored) {
            // An invalid external header is ignored; the streaming limit remains authoritative.
        }
    }

    private byte[] readBounded(InputStream input, long deadline)
            throws IOException, UrlFetchException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) != -1) {
            ensureBeforeDeadline(deadline);
            if (output.size() > properties.maxBodyBytes() - count) {
                throw tooLarge();
            }
            output.write(buffer, 0, count);
        }
        ensureBeforeDeadline(deadline);
        return output.toByteArray();
    }

    private void ensureBeforeDeadline(long deadline) throws UrlFetchException {
        if (nanoTime.getAsLong() >= deadline) {
            throw timeout();
        }
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301
                || statusCode == 302
                || statusCode == 303
                || statusCode == 307
                || statusCode == 308;
    }

    private static UrlFetchException timeout() {
        return new UrlFetchException(Reason.TIMEOUT, "URL fetch deadline exceeded");
    }

    private static UrlFetchException tooLarge() {
        return new UrlFetchException(Reason.RESPONSE_TOO_LARGE, "Response body limit exceeded");
    }
}
