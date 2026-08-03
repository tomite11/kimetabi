package app.tabikime.kimetabi.async;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
final class GoogleCloudTasksGateway implements MetadataTaskGateway {

    private static final String CLOUD_PLATFORM_SCOPE =
            "https://www.googleapis.com/auth/cloud-platform";

    private final CloudTasksProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private GoogleCredentials credentials;

    @Autowired
    GoogleCloudTasksGateway(CloudTasksProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    GoogleCloudTasksGateway(
            CloudTasksProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public void create(UUID eventId, long candidateId) throws IOException {
        if (!properties.configured()) {
            throw new IOException("Cloud Tasks is not configured");
        }
        String queuePath = "projects/%s/locations/%s/queues/%s".formatted(
                properties.projectId(), properties.location(), properties.queue());
        URI endpoint = URI.create(
                "https://cloudtasks.googleapis.com/v2/" + queuePath + "/tasks");
        URI handler = properties.handlerBaseUrl().resolve(
                "/internal/tasks/candidates/" + candidateId + "/metadata");
        byte[] taskBody;
        try {
            taskBody = objectMapper.writeValueAsBytes(Map.of(
                    "eventId", eventId,
                    "candidateId", candidateId));
        } catch (JacksonException exception) {
            throw new IOException("Could not serialize metadata task", exception);
        }
        Map<String, Object> requestBody = Map.of("task", Map.of(
                "name", queuePath + "/tasks/" + eventId,
                "httpRequest", Map.of(
                        "httpMethod", "POST",
                        "url", handler.toString(),
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Base64.getEncoder().encodeToString(taskBody),
                        "oidcToken", Map.of(
                                "serviceAccountEmail", properties.serviceAccountEmail(),
                                "audience", properties.audience()))));
        String json;
        try {
            json = objectMapper.writeValueAsString(requestBody);
        } catch (JacksonException exception) {
            throw new IOException("Could not serialize Cloud Tasks request", exception);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            if ((response.statusCode() < 200 || response.statusCode() >= 300)
                    && response.statusCode() != 409) {
                throw new IOException(
                        "Cloud Tasks create failed with status " + response.statusCode());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Cloud Tasks create was interrupted", exception);
        }
    }

    private synchronized String accessToken() throws IOException {
        if (credentials == null) {
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(CLOUD_PLATFORM_SCOPE);
        }
        credentials.refreshIfExpired();
        if (credentials.getAccessToken() == null) {
            credentials.refresh();
        }
        return credentials.getAccessToken().getTokenValue();
    }
}
