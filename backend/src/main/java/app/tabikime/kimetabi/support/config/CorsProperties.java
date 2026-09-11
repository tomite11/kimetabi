package app.tabikime.kimetabi.support.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kimetabi.cors")
public record CorsProperties(List<String> allowedOrigins, Duration maxAge) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        maxAge = maxAge == null ? Duration.ofHours(1) : maxAge;
        if (allowedOrigins.stream().anyMatch(origin -> !isExactHttpsOrigin(origin))) {
            throw new IllegalArgumentException("CORS origins must be exact HTTPS origins");
        }
    }

    private static boolean isExactHttpsOrigin(String origin) {
        if (origin == null || origin.isBlank() || origin.contains("*")) return false;
        try {
            URI uri = new URI(origin);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
