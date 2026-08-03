package app.tabikime.kimetabi.ingestion.url;

import java.net.URI;
import java.util.List;
import java.util.Map;

public record FetchResult(
        URI finalUrl,
        int statusCode,
        Map<String, List<String>> headers,
        byte[] body
) {

    public FetchResult {
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    public FetchResult(URI finalUrl, int statusCode, byte[] body) {
        this(finalUrl, statusCode, Map.of(), body);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
