package app.tabikime.kimetabi.ingestion.metadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import app.tabikime.kimetabi.ingestion.metadata.MetadataExtractionException.Disposition;
import app.tabikime.kimetabi.ingestion.url.FetchResult;
import app.tabikime.kimetabi.ingestion.url.SafeUrlFetcher;
import app.tabikime.kimetabi.ingestion.url.UrlFetchException;

@Service
public class UrlMetadataExtractor {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_IMAGE_URL_LENGTH = 2048;
    private final SafeUrlFetcher fetcher;

    public UrlMetadataExtractor(SafeUrlFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public ExtractedMetadata extract(String rawUrl) throws MetadataExtractionException {
        FetchResult result;
        try {
            result = fetcher.fetch(URI.create(rawUrl));
        } catch (IllegalArgumentException exception) {
            throw permanent("INVALID_URL", "Metadata URL is invalid", exception);
        } catch (UrlFetchException exception) {
            throw classify(exception);
        }

        int status = result.statusCode();
        if (status == 429) {
            throw retryable("HTTP_429", "Metadata site requested temporary rate limiting");
        }
        if (status >= 500 && status <= 599) {
            throw retryable("HTTP_5XX", "Metadata site returned a temporary server error");
        }
        if (status < 200 || status >= 300) {
            throw permanent("HTTP_4XX", "Metadata site returned a permanent HTTP error");
        }

        try {
            Document document = Jsoup.parse(
                    new ByteArrayInputStream(result.body()),
                    null,
                    result.finalUrl().toString());
            String title = content(document.selectFirst("meta[property=og:title]"));
            if (title == null) title = blankToNull(document.title());

            String imageUrl = absoluteContent(
                    document.selectFirst("meta[property=og:image]"), result.finalUrl());
            if (imageUrl == null) {
                Element icon = document.select("link[rel]").stream()
                        .filter(UrlMetadataExtractor::isIcon)
                        .findFirst()
                        .orElse(null);
                imageUrl = absoluteHref(icon, result.finalUrl());
            }
            if (title != null && title.length() > MAX_TITLE_LENGTH) {
                throw permanent("INVALID_METADATA", "Metadata title exceeds the field limit");
            }
            if (imageUrl != null && imageUrl.length() > MAX_IMAGE_URL_LENGTH) {
                throw permanent("INVALID_METADATA", "Metadata image URL exceeds the field limit");
            }
            return new ExtractedMetadata(title, imageUrl);
        } catch (MetadataExtractionException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw permanent("INVALID_METADATA", "Metadata response could not be parsed", exception);
        }
    }

    private static MetadataExtractionException classify(UrlFetchException exception) {
        return switch (exception.reason()) {
            case DNS_FAILURE -> retryable("DNS_FAILURE", "Metadata host resolution failed", exception);
            case INVALID_URL -> permanent("INVALID_URL", "Metadata URL is invalid", exception);
            case BLOCKED_ADDRESS -> permanent("SSRF_REJECTED", "Metadata address was rejected", exception);
            case TOO_MANY_REDIRECTS -> permanent(
                    "REDIRECT_LIMIT", "Metadata redirect limit was exceeded", exception);
            case RESPONSE_TOO_LARGE -> permanent(
                    "RESPONSE_TOO_LARGE", "Metadata response exceeded the size limit", exception);
            case TIMEOUT -> retryable(
                    "FETCH_TIMEOUT", "Metadata fetch timed out", exception);
            case TRANSPORT_FAILURE -> retryable(
                    "TRANSPORT_FAILURE", "Metadata transport failed temporarily", exception);
            case TLS_VALIDATION_FAILURE -> permanent(
                    "TLS_VALIDATION_FAILURE", "Metadata TLS identity could not be verified", exception);
            case INVALID_RESPONSE -> permanent(
                    "INVALID_RESPONSE", "Metadata site returned an invalid HTTP response", exception);
        };
    }

    private static String content(Element element) {
        return element == null ? null : blankToNull(element.attr("content"));
    }

    private static String absoluteContent(Element element, URI base) {
        return element == null ? null : resolveHttpUrl(base, element.attr("content"));
    }

    private static String absoluteHref(Element element, URI base) {
        return element == null ? null : resolveHttpUrl(base, element.attr("href"));
    }

    private static boolean isIcon(Element element) {
        String rel = element.attr("rel").trim().toLowerCase(java.util.Locale.ROOT);
        return rel.equals("icon") || rel.equals("shortcut icon")
                || rel.equals("apple-touch-icon");
    }

    private static String resolveHttpUrl(URI base, String value) {
        String normalized = blankToNull(value);
        if (normalized == null) return null;
        URI resolved = base.resolve(normalized);
        if (!"http".equalsIgnoreCase(resolved.getScheme())
                && !"https".equalsIgnoreCase(resolved.getScheme())) {
            return null;
        }
        return resolved.toString();
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static MetadataExtractionException retryable(String code, String message) {
        return new MetadataExtractionException(Disposition.RETRYABLE, code, message);
    }

    private static MetadataExtractionException retryable(
            String code, String message, Throwable cause) {
        return new MetadataExtractionException(Disposition.RETRYABLE, code, message, cause);
    }

    private static MetadataExtractionException permanent(
            String code, String message, Throwable cause) {
        return new MetadataExtractionException(Disposition.PERMANENT, code, message, cause);
    }

    private static MetadataExtractionException permanent(String code, String message) {
        return new MetadataExtractionException(Disposition.PERMANENT, code, message);
    }

}
