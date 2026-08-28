package app.tabikime.kimetabi.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.AuthenticationException;

import app.tabikime.kimetabi.identity.AppPrincipal;
import app.tabikime.kimetabi.identity.FirebaseTokenVerificationException;
import app.tabikime.kimetabi.identity.FirebaseTokenVerifier;
import app.tabikime.kimetabi.identity.VerifiedFirebaseToken;

class StompFirebaseAuthenticationInterceptorTest {

    private static final MessageChannel CHANNEL = mock(MessageChannel.class);

    @Test
    void authenticatesConnectWithVerifiedFirebaseIdToken() {
        FirebaseTokenVerifier verifier = token -> {
            assertThat(token).isEqualTo("valid-token");
            return new VerifiedFirebaseToken("member-uid");
        };
        var interceptor = new StompFirebaseAuthenticationInterceptor(Optional.of(verifier));
        Message<byte[]> message = connect("Bearer valid-token");

        interceptor.preSend(message, CHANNEL);

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("member-uid");
        assertThat(((org.springframework.security.core.Authentication) accessor.getUser())
                .getPrincipal()).isEqualTo(new AppPrincipal("member-uid"));
    }

    @Test
    void rejectsMissingInvalidAndUnavailableVerifier() {
        FirebaseTokenVerifier invalid = token -> {
            throw new FirebaseTokenVerificationException("invalid fixture");
        };

        assertThatThrownBy(() -> new StompFirebaseAuthenticationInterceptor(Optional.of(invalid))
                .preSend(connect("Bearer invalid"), CHANNEL))
                .isInstanceOf(AuthenticationException.class);
        assertThatThrownBy(() -> new StompFirebaseAuthenticationInterceptor(Optional.of(invalid))
                .preSend(connect(null), CHANNEL))
                .isInstanceOf(AuthenticationException.class);
        assertThatThrownBy(() -> new StompFirebaseAuthenticationInterceptor(Optional.empty())
                .preSend(connect("Bearer token"), CHANNEL))
                .isInstanceOf(AuthenticationException.class);
    }

    private static Message<byte[]> connect(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorization != null) {
            accessor.setNativeHeader(
                    StompFirebaseAuthenticationInterceptor.AUTHORIZATION_HEADER,
                    authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
