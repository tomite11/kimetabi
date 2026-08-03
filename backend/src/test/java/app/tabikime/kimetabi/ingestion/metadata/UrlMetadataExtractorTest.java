package app.tabikime.kimetabi.ingestion.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import app.tabikime.kimetabi.ingestion.metadata.MetadataExtractionException.Disposition;
import app.tabikime.kimetabi.ingestion.url.PinnedHttpTransport;
import app.tabikime.kimetabi.ingestion.url.SafeUrlFetcher;
import app.tabikime.kimetabi.ingestion.url.UrlAddressValidator;
import app.tabikime.kimetabi.ingestion.url.UrlFetchProperties;

class UrlMetadataExtractorTest {

    private static final UrlFetchProperties PROPERTIES = new UrlFetchProperties(
            Duration.ofSeconds(3), Duration.ofSeconds(5), 2 * 1024 * 1024, 5, 2);

    @Test
    void extractsOgpBeforeHtmlFallbackAndResolvesRelativeImage() throws Exception {
        String html = """
                <html><head>
                  <meta property="og:title" content=" OGP Hotel ">
                  <meta property="og:image" content="/images/hotel.jpg">
                  <title>HTML Hotel</title>
                  <link rel="icon" href="/favicon.ico">
                </head></html>
                """;

        ExtractedMetadata metadata = extractor(200, html)
                .extract("https://public.example/stays/1");

        assertThat(metadata.title()).isEqualTo("OGP Hotel");
        assertThat(metadata.imageUrl())
                .isEqualTo("https://public.example/images/hotel.jpg");
    }

    @Test
    void fallsBackToHtmlTitleAndFavicon() throws Exception {
        String html = """
                <html><head><title>Fallback Hotel</title>
                <link rel="shortcut icon" href="icon.png"></head></html>
                """;

        ExtractedMetadata metadata = extractor(200, html)
                .extract("https://public.example/stays/");

        assertThat(metadata.title()).isEqualTo("Fallback Hotel");
        assertThat(metadata.imageUrl())
                .isEqualTo("https://public.example/stays/icon.png");
    }

    @Test
    void classifiesDnsAndFiveHundredsAsRetryable() {
        UrlMetadataExtractor dnsExtractor = new UrlMetadataExtractor(new SafeUrlFetcher(
                new UrlAddressValidator(host -> {
                    throw new java.net.UnknownHostException(host);
                }),
                responseTransport(200, ""),
                PROPERTIES));

        assertThatThrownBy(() -> dnsExtractor.extract("https://missing.example"))
                .isInstanceOfSatisfying(MetadataExtractionException.class, exception -> {
                    assertThat(exception.disposition()).isEqualTo(Disposition.RETRYABLE);
                    assertThat(exception.errorCode()).isEqualTo("DNS_FAILURE");
                });
        assertThatThrownBy(() -> extractor(503, "untrusted body")
                .extract("https://public.example"))
                .isInstanceOfSatisfying(MetadataExtractionException.class, exception -> {
                    assertThat(exception.disposition()).isEqualTo(Disposition.RETRYABLE);
                    assertThat(exception.errorCode()).isEqualTo("HTTP_5XX");
                });
    }

    @Test
    void classifiesHttp429AsRetryable() {
        assertThatThrownBy(() -> extractor(429, "rate limited")
                .extract("https://public.example"))
                .isInstanceOfSatisfying(MetadataExtractionException.class, exception -> {
                    assertThat(exception.disposition()).isEqualTo(Disposition.RETRYABLE);
                    assertThat(exception.errorCode()).isEqualTo("HTTP_429");
                });
    }

    @Test
    void classifiesTimeoutAsRetryable() {
        SafeUrlFetcher fetcher = new SafeUrlFetcher(
                new UrlAddressValidator(host -> List.of(
                        InetAddress.getByName("93.184.216.34"))),
                (target, address, connectTimeout, operationTimeout) -> {
                    throw new java.net.SocketTimeoutException("fixture");
                },
                PROPERTIES);

        assertThatThrownBy(() -> new UrlMetadataExtractor(fetcher)
                .extract("https://public.example"))
                .isInstanceOfSatisfying(MetadataExtractionException.class, exception -> {
                    assertThat(exception.disposition()).isEqualTo(Disposition.RETRYABLE);
                    assertThat(exception.errorCode()).isEqualTo("FETCH_TIMEOUT");
                });
    }

    private static UrlMetadataExtractor extractor(int status, String body) {
        SafeUrlFetcher fetcher = new SafeUrlFetcher(
                new UrlAddressValidator(host -> List.of(
                        InetAddress.getByName("93.184.216.34"))),
                responseTransport(status, body),
                PROPERTIES);
        return new UrlMetadataExtractor(fetcher);
    }

    private static PinnedHttpTransport responseTransport(int status, String body) {
        return (target, address, connectTimeout, operationTimeout) ->
                new PinnedHttpTransport.Response(
                        status,
                        Map.of("Content-Type", List.of("text/html; charset=UTF-8")),
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
