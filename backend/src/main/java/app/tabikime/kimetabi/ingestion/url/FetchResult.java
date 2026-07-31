package app.tabikime.kimetabi.ingestion.url;

import java.net.URI;

public record FetchResult(URI finalUrl, int statusCode, byte[] body) {

    public FetchResult {
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
