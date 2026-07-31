package app.tabikime.kimetabi.ingestion.url;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kimetabi.url-fetch")
public record UrlFetchProperties(
        Duration connectTimeout,
        Duration totalTimeout,
        int maxBodyBytes,
        int maxRedirects
) {

    public static final Set<Integer> ALLOWED_PORTS = Set.of(80, 443);

    public UrlFetchProperties {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (totalTimeout == null || totalTimeout.compareTo(connectTimeout) < 0) {
            throw new IllegalArgumentException("totalTimeout must be at least connectTimeout");
        }
        if (maxBodyBytes < 1 || maxRedirects < 0) {
            throw new IllegalArgumentException("fetch limits must be non-negative");
        }
    }
}
