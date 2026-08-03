package app.tabikime.kimetabi.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;

class FirebaseAdminTokenVerifierTest {

    private final FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
    private final FirebaseAdminTokenVerifier verifier =
            new FirebaseAdminTokenVerifier(firebaseAuth);

    @Test
    void allowsCredentialFreeAuthEmulatorOnlyForDemoProjects() throws Exception {
        assertThat(FirebaseAdminConfiguration.credentials(
                "demo-kimetabi-e2e", "127.0.0.1:9099")).isNotNull();

        assertThatThrownBy(() -> FirebaseAdminConfiguration.credentials(
                "production-project", "127.0.0.1:9099"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo-");
    }

    @Test
    void returnsOnlyUidFromVerifiedToken() throws Exception {
        FirebaseToken firebaseToken = mock(FirebaseToken.class);
        when(firebaseToken.getUid()).thenReturn("firebase-user-1");
        when(firebaseAuth.verifyIdToken("signed-id-token")).thenReturn(firebaseToken);

        VerifiedFirebaseToken actual = verifier.verify("signed-id-token");

        assertThat(actual.uid()).isEqualTo("firebase-user-1");
        verify(firebaseAuth).verifyIdToken("signed-id-token");
    }

    @Test
    void rejectsTokenWhenFirebaseValidationFails() throws Exception {
        FirebaseAuthException cause = mock(FirebaseAuthException.class);
        when(firebaseAuth.verifyIdToken("invalid-token")).thenThrow(cause);

        assertThatThrownBy(() -> verifier.verify("invalid-token"))
                .isInstanceOf(FirebaseTokenVerificationException.class)
                .hasMessage("Firebase ID token verification failed")
                .hasCause(cause);
    }

    @Test
    void rejectsVerifiedTokenWithoutUid() throws Exception {
        FirebaseToken firebaseToken = mock(FirebaseToken.class);
        when(firebaseToken.getUid()).thenReturn("");
        when(firebaseAuth.verifyIdToken("token-without-uid")).thenReturn(firebaseToken);

        assertThatThrownBy(() -> verifier.verify("token-without-uid"))
                .isInstanceOf(FirebaseTokenVerificationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
