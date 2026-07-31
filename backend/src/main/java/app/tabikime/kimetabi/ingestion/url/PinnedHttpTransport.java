package app.tabikime.kimetabi.ingestion.url;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface PinnedHttpTransport {

    Response execute(
            ValidatedUrl target,
            InetAddress pinnedAddress,
            Duration connectTimeout,
            Duration operationTimeout
    ) throws IOException;

    record Response(int statusCode, Map<String, List<String>> headers, InputStream decodedBody)
            implements AutoCloseable {

        public Response {
            headers = Map.copyOf(headers);
        }

        public String firstHeader(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public void close() throws IOException {
            decodedBody.close();
        }
    }
}
