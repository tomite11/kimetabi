package app.tabikime.kimetabi.async;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kimetabi.cloud-tasks")
public record CloudTasksProperties(
        String projectId,
        String location,
        String queue,
        URI handlerBaseUrl,
        String serviceAccountEmail,
        String audience,
        Duration requestTimeout
) {

    public boolean configured() {
        return notBlank(projectId) && notBlank(location) && notBlank(queue)
                && handlerBaseUrl != null && notBlank(serviceAccountEmail)
                && notBlank(audience) && requestTimeout != null
                && !requestTimeout.isNegative() && !requestTimeout.isZero();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
