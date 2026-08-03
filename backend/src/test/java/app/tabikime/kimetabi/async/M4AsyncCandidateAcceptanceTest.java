package app.tabikime.kimetabi.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import app.tabikime.kimetabi.identity.AppPrincipal;
import app.tabikime.kimetabi.ingestion.metadata.ExtractedMetadata;
import app.tabikime.kimetabi.ingestion.metadata.MetadataExtractionException;
import app.tabikime.kimetabi.ingestion.metadata.UrlMetadataExtractor;
import app.tabikime.kimetabi.internal.InternalCaller;
import app.tabikime.kimetabi.internal.InternalOidcVerificationException;
import app.tabikime.kimetabi.internal.InternalOidcVerifier;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(M4AsyncCandidateAcceptanceTest.Configuration.class)
class M4AsyncCandidateAcceptanceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("kimetabi")
                    .withUsername("kimetabi")
                    .withPassword("kimetabi");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private RecordingTaskGateway taskGateway;

    @Autowired
    private UrlMetadataExtractor metadataExtractor;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("""
                        TRUNCATE idempotency_request, trip_member, trip
                        RESTART IDENTITY CASCADE
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO trip (
                            id, title, destination, starts_on, ends_on, timezone,
                            expected_member_count
                        ) VALUES (
                            1, '旅行', '東京', DATE '2030-08-01', DATE '2030-08-03',
                            'Asia/Tokyo', 3
                        )
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO trip_member (
                            id, trip_id, firebase_uid, name, role, status
                        ) VALUES (1, 1, 'owner-a', 'Owner', 'OWNER', 'ACTIVE')
                        """).update();
        jdbcClient.sql("UPDATE trip SET owner_member_id = 1 WHERE id = 1").update();
        jdbcClient.sql("""
                        INSERT INTO slot (
                            id, trip_id, category, title, day_from, day_to,
                            units, sort_order, status
                        ) VALUES (1, 1, 'LODGING', '宿', 1, 2, 2, 0, 'OPEN')
                        """).update();
        taskGateway.tasks.clear();
    }

    @Test
    void delayedSiteConvergesThroughAuthenticatedTaskAndDuplicateDeliveryIsHarmless()
            throws Exception {
        String url = "https://slow.example/hotel";
        when(metadataExtractor.extract(url))
                .thenThrow(new MetadataExtractionException(
                        MetadataExtractionException.Disposition.RETRYABLE,
                        "FETCH_TIMEOUT",
                        "fixture timeout"))
                .thenReturn(new ExtractedMetadata(
                        "遅れて取得したホテル", "https://slow.example/hotel.jpg"));

        createUrlCandidate(url)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.metadataStatus").value("PENDING"));
        assertThat(dispatcher.dispatch(10).published()).isEqualTo(1);
        CreatedTask firstTask = taskGateway.tasks.getFirst();

        performTask(firstTask, null).andExpect(status().isUnauthorized());
        performTask(firstTask, "firebase-token").andExpect(status().isUnauthorized());
        performTask(firstTask, "scheduler-token").andExpect(status().isUnauthorized());
        performTask(firstTask, "tasks-token").andExpect(status().isInternalServerError());

        mockMvc.perform(get("/api/trips/1/slots/1").with(principal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].metadataStatus")
                        .value("FAILED_RETRYABLE"))
                .andExpect(jsonPath("$.candidates[0].metadataErrorCode")
                        .value("FETCH_TIMEOUT"));

        mockMvc.perform(post("/api/trips/1/candidates/1/metadata/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2}")
                        .with(principal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadataStatus").value("PENDING"));
        assertThat(dispatcher.dispatch(10).published()).isEqualTo(1);
        CreatedTask retryTask = taskGateway.tasks.get(1);

        performTask(retryTask, "tasks-token").andExpect(status().isNoContent());
        performTask(retryTask, "tasks-token").andExpect(status().isNoContent());

        mockMvc.perform(get("/api/trips/1/slots/1").with(principal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].metadataStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.candidates[0].title").value("遅れて取得したホテル"))
                .andExpect(jsonPath("$.candidates[0].imageUrl")
                        .value("https://slow.example/hotel.jpg"));
        verify(metadataExtractor, org.mockito.Mockito.times(2)).extract(url);
    }

    @Test
    void stoppedSiteConvergesToManualInputAfterPermanentFailure() throws Exception {
        String url = "https://stopped.example/hotel";
        when(metadataExtractor.extract(url)).thenThrow(new MetadataExtractionException(
                MetadataExtractionException.Disposition.PERMANENT,
                "HTTP_NOT_FOUND",
                "fixture not found"));

        createUrlCandidate(url).andExpect(status().isCreated());
        assertThat(dispatcher.dispatch(10).published()).isEqualTo(1);
        performTask(taskGateway.tasks.getFirst(), "tasks-token")
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/trips/1/candidates/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version":2,
                                  "title":"手入力したホテル",
                                  "estAmount":18000,
                                  "estBasis":"PER_PERSON"
                                }
                                """)
                        .with(principal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("手入力したホテル"))
                .andExpect(jsonPath("$.metadataStatus").value("FAILED_PERMANENT"));

        mockMvc.perform(get("/api/trips/1/slots/1").with(principal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].title").value("手入力したホテル"))
                .andExpect(jsonPath("$.candidates[0].estAmount").value(18000));
    }

    private org.springframework.test.web.servlet.ResultActions createUrlCandidate(String url)
            throws Exception {
        return mockMvc.perform(post("/api/trips/1/slots/1/candidates")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + url + "\"}")
                .with(principal()));
    }

    private org.springframework.test.web.servlet.ResultActions performTask(
            CreatedTask task,
            String token
    ) throws Exception {
        var request = post("/internal/tasks/candidates/{candidateId}/metadata", task.candidateId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"eventId":"%s","candidateId":%d}
                        """.formatted(task.eventId(), task.candidateId()));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor principal() {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new AppPrincipal("owner-a"), "token", List.of()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        RecordingTaskGateway recordingTaskGateway() {
            return new RecordingTaskGateway();
        }

        @Bean
        @Primary
        UrlMetadataExtractor metadataExtractor() {
            return mock(UrlMetadataExtractor.class);
        }

        @Bean
        @Primary
        InternalOidcVerifier internalOidcVerifier() {
            return (token, caller) -> {
                if (caller != InternalCaller.CLOUD_TASKS || !"tasks-token".equals(token)) {
                    throw new InternalOidcVerificationException("invalid task identity");
                }
            };
        }
    }

    static final class RecordingTaskGateway implements MetadataTaskGateway {

        private final List<CreatedTask> tasks = new ArrayList<>();

        @Override
        public void create(UUID eventId, long candidateId) {
            tasks.add(new CreatedTask(eventId, candidateId));
        }
    }

    record CreatedTask(UUID eventId, long candidateId) {
    }
}
