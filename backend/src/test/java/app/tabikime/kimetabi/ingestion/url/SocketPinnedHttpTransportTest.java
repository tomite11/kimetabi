package app.tabikime.kimetabi.ingestion.url;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

class SocketPinnedHttpTransportTest {

    @Test
    void connectsToPinnedAddressWithoutResolvingHostname() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, loopback);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                try (var socket = server.accept();
                        var reader = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII))) {
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // Consume request headers before responding.
                    }
                    socket.getOutputStream().write(("""
                            HTTP/1.1 200 OK\r
                            Content-Length: 5\r
                            Connection: close\r
                            \r
                            hello""").getBytes(StandardCharsets.US_ASCII));
                }
                return null;
            });
            ValidatedUrl target = new ValidatedUrl(
                    URI.create("http://does-not-resolve.invalid:" + server.getLocalPort() + "/x"),
                    "does-not-resolve.invalid",
                    server.getLocalPort(),
                    List.of(loopback));

            try (var response = new SocketPinnedHttpTransport().execute(
                    target, loopback, Duration.ofSeconds(1), Duration.ofSeconds(1))) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.decodedBody().readAllBytes())
                        .isEqualTo("hello".getBytes(StandardCharsets.US_ASCII));
            }
        }
    }

    @Test
    void enforcesOperationTimeoutWhileReadingHeaders() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, loopback);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                try (var socket = server.accept()) {
                    Thread.sleep(Duration.ofMillis(500));
                }
                return null;
            });
            ValidatedUrl target = new ValidatedUrl(
                    URI.create("http://timeout.invalid:" + server.getLocalPort() + "/"),
                    "timeout.invalid",
                    server.getLocalPort(),
                    List.of(loopback));

            assertThatThrownBy(() -> new SocketPinnedHttpTransport().execute(
                    target, loopback, Duration.ofSeconds(1), Duration.ofMillis(50)))
                    .isInstanceOf(SocketTimeoutException.class);
        }
    }
}
