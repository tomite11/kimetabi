package app.tabikime.kimetabi.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.auth.oauth2.TokenVerifier;
import org.junit.jupiter.api.Test;

class GoogleInternalOidcVerifierTest {

    private static final InternalOidcProperties PROPERTIES = new InternalOidcProperties(
            new InternalOidcProperties.Identity(
                    "https://api.example.com/internal/tasks",
                    "kimetabi-tasks-invoker@example.iam.gserviceaccount.com"),
            new InternalOidcProperties.Identity(
                    "https://api.example.com/internal/outbox",
                    "kimetabi-scheduler-invoker@example.iam.gserviceaccount.com"));

    private final TokenVerifier tasksTokenVerifier = mock(TokenVerifier.class);
    private final TokenVerifier schedulerTokenVerifier = mock(TokenVerifier.class);
    private final GoogleInternalOidcVerifier verifier = new GoogleInternalOidcVerifier(
            PROPERTIES, tasksTokenVerifier, schedulerTokenVerifier);

    @Test
    void usesCallerSpecificAudienceVerifierAndAcceptsOnlyVerifiedServiceAccount()
            throws Exception {
        when(tasksTokenVerifier.verify("tasks-jwt")).thenReturn(token(
                "kimetabi-tasks-invoker@example.iam.gserviceaccount.com", true));

        assertThatCode(() -> verifier.verify("tasks-jwt", InternalCaller.CLOUD_TASKS))
                .doesNotThrowAnyException();
        verify(tasksTokenVerifier).verify("tasks-jwt");
        verify(schedulerTokenVerifier, never()).verify("tasks-jwt");
    }

    @Test
    void rejectsWrongServiceAccountAndUnverifiedEmail() throws Exception {
        when(tasksTokenVerifier.verify("scheduler-jwt")).thenReturn(token(
                "kimetabi-scheduler-invoker@example.iam.gserviceaccount.com", true));
        when(tasksTokenVerifier.verify("unverified-jwt")).thenReturn(token(
                "kimetabi-tasks-invoker@example.iam.gserviceaccount.com", false));

        assertThatThrownBy(() -> verifier.verify(
                "scheduler-jwt", InternalCaller.CLOUD_TASKS))
                .isInstanceOf(InternalOidcVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(
                "unverified-jwt", InternalCaller.CLOUD_TASKS))
                .isInstanceOf(InternalOidcVerificationException.class);
    }

    @Test
    void failsClosedWhenCallerIdentityIsNotConfigured() {
        var unconfigured = new InternalOidcProperties.Identity("", "");
        var unconfiguredVerifier = new GoogleInternalOidcVerifier(
                new InternalOidcProperties(unconfigured, unconfigured), null, null);

        assertThatThrownBy(() -> unconfiguredVerifier.verify(
                "token", InternalCaller.CLOUD_TASKS))
                .isInstanceOf(InternalOidcVerificationException.class)
                .hasMessage("Internal OIDC identity is not configured");
    }

    private static JsonWebSignature token(String email, boolean emailVerified) {
        JsonWebToken.Payload payload = new JsonWebToken.Payload()
                .set("email", email)
                .set("email_verified", emailVerified);
        return new JsonWebSignature(new JsonWebSignature.Header(), payload, new byte[0], new byte[0]);
    }
}
