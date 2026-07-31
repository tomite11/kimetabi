package app.tabikime.kimetabi.ingestion.url;

import static app.tabikime.kimetabi.ingestion.url.UrlFetchException.Reason.BLOCKED_ADDRESS;
import static app.tabikime.kimetabi.ingestion.url.UrlFetchException.Reason.RESPONSE_TOO_LARGE;
import static app.tabikime.kimetabi.ingestion.url.UrlFetchException.Reason.TIMEOUT;
import static app.tabikime.kimetabi.ingestion.url.UrlFetchException.Reason.TOO_MANY_REDIRECTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import app.tabikime.kimetabi.ingestion.url.PinnedHttpTransport.Response;

class SafeUrlFetcherTest {

    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final UrlFetchProperties PROPERTIES =
            new UrlFetchProperties(Duration.ofSeconds(3), Duration.ofSeconds(5), MAX_BODY_BYTES, 5);

    @Test
    void validatesAndPinsEveryRedirectHop() throws Exception {
        AddressResolver resolver = hostname -> switch (hostname) {
            case "start.example" -> List.of(InetAddress.getByName("93.184.216.34"));
            case "next.example" -> List.of(InetAddress.getByName("8.8.8.8"));
            default -> throw new java.net.UnknownHostException(hostname);
        };
        FakeTransport transport = new FakeTransport(
                redirect(302, "https://next.example/final"),
                response(200, "metadata"));
        SafeUrlFetcher fetcher = fetcher(resolver, transport);

        FetchResult result = fetcher.fetch(URI.create("https://start.example/link"));

        assertThat(result.finalUrl()).isEqualTo(URI.create("https://next.example/final"));
        assertThat(result.body()).isEqualTo("metadata".getBytes());
        assertThat(transport.calls)
                .extracting(call -> call.uri)
                .containsExactly(
                        URI.create("https://start.example/link"),
                        URI.create("https://next.example/final"));
        assertThat(transport.calls)
                .extracting(call -> call.pinnedAddress.getHostAddress())
                .containsExactly("93.184.216.34", "8.8.8.8");
    }

    @Test
    void rejectsRedirectToPrivateAddressBeforeSecondConnection() throws Exception {
        AddressResolver resolver = hostname -> switch (hostname) {
            case "start.example" -> List.of(InetAddress.getByName("93.184.216.34"));
            case "internal.example" -> List.of(InetAddress.getByName("10.0.0.1"));
            default -> throw new java.net.UnknownHostException(hostname);
        };
        FakeTransport transport =
                new FakeTransport(redirect(302, "http://internal.example/admin"));
        SafeUrlFetcher fetcher = fetcher(resolver, transport);

        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://start.example")))
                .isInstanceOfSatisfying(
                        UrlFetchException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(BLOCKED_ADDRESS));
        assertThat(transport.calls).hasSize(1);
    }

    @Test
    void rejectsSixthRedirectWithoutConnectingToItsTarget() throws Exception {
        AddressResolver resolver =
                hostname -> List.of(InetAddress.getByName("93.184.216.34"));
        FakeTransport transport = new FakeTransport(
                redirect(302, "/1"),
                redirect(302, "/2"),
                redirect(302, "/3"),
                redirect(302, "/4"),
                redirect(302, "/5"),
                redirect(302, "/6"));
        SafeUrlFetcher fetcher = fetcher(resolver, transport);

        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://start.example/0")))
                .isInstanceOfSatisfying(
                        UrlFetchException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(TOO_MANY_REDIRECTS));
        assertThat(transport.calls).hasSize(6);
    }

    @Test
    void rejectsOversizedContentLengthBeforeReadingBody() {
        TrackingInputStream body = new TrackingInputStream(new byte[0]);
        FakeTransport transport = new FakeTransport(new Response(
                200,
                Map.of("Content-Length", List.of(String.valueOf(MAX_BODY_BYTES + 1L))),
                body));
        SafeUrlFetcher fetcher = fetcher(publicResolver(), transport);

        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://public.example")))
                .isInstanceOfSatisfying(
                        UrlFetchException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(RESPONSE_TOO_LARGE));
        assertThat(body.readAttempted).isFalse();
    }

    @Test
    void rejectsChunkedBodyAfterExpandedBytesExceedLimit() {
        byte[] bytes = new byte[MAX_BODY_BYTES + 1];
        FakeTransport transport = new FakeTransport(new Response(
                200,
                Map.of("Content-Encoding", List.of("gzip")),
                new ByteArrayInputStream(bytes)));
        SafeUrlFetcher fetcher = fetcher(publicResolver(), transport);

        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://public.example")))
                .isInstanceOfSatisfying(
                        UrlFetchException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(RESPONSE_TOO_LARGE));
    }

    @Test
    void passesThreeSecondConnectAndSingleFiveSecondOperationLimitsToTransport() throws Exception {
        FakeTransport transport = new FakeTransport(response(200, "ok"));
        SafeUrlFetcher fetcher = fetcher(publicResolver(), transport);

        fetcher.fetch(URI.create("https://public.example"));

        assertThat(transport.calls).singleElement().satisfies(call -> {
            assertThat(call.connectTimeout).isEqualTo(Duration.ofSeconds(3));
            assertThat(call.operationTimeout).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void enforcesSingleDeadlineAcrossRedirects() {
        AtomicLong clock = new AtomicLong();
        FakeTransport transport = new FakeTransport(redirect(302, "/next"));
        transport.afterCall = () -> clock.set(Duration.ofSeconds(5).toNanos());
        SafeUrlFetcher fetcher = new SafeUrlFetcher(
                new UrlAddressValidator(publicResolver()),
                transport,
                PROPERTIES,
                clock::get);

        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://public.example/start")))
                .isInstanceOfSatisfying(
                        UrlFetchException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(TIMEOUT));
        assertThat(transport.calls).hasSize(1);
    }

    private static SafeUrlFetcher fetcher(
            AddressResolver resolver,
            PinnedHttpTransport transport
    ) {
        return new SafeUrlFetcher(new UrlAddressValidator(resolver), transport, PROPERTIES);
    }

    private static AddressResolver publicResolver() {
        return hostname -> List.of(InetAddress.getByName("93.184.216.34"));
    }

    private static Response redirect(int status, String location) {
        return new Response(
                status,
                Map.of("Location", List.of(location)),
                new ByteArrayInputStream(new byte[0]));
    }

    private static Response response(int status, String body) {
        return new Response(
                status,
                Map.of(),
                new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static final class FakeTransport implements PinnedHttpTransport {

        private final Queue<Response> responses = new ArrayDeque<>();
        private final List<Call> calls = new ArrayList<>();
        private Runnable afterCall = () -> {
        };

        private FakeTransport(Response... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public Response execute(
                ValidatedUrl target,
                InetAddress pinnedAddress,
                Duration connectTimeout,
                Duration operationTimeout
        ) {
            calls.add(new Call(target.uri(), pinnedAddress, connectTimeout, operationTimeout));
            afterCall.run();
            Response response = responses.poll();
            if (response == null) {
                throw new AssertionError("Unexpected transport call");
            }
            return response;
        }
    }

    private record Call(
            URI uri,
            InetAddress pinnedAddress,
            Duration connectTimeout,
            Duration operationTimeout
    ) {
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private boolean readAttempted;

        private TrackingInputStream(byte[] buffer) {
            super(buffer);
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            readAttempted = true;
            return super.read(bytes, offset, length);
        }
    }
}
