package app.tabikime.kimetabi.realtime;

import java.util.List;
import java.util.Optional;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;

import app.tabikime.kimetabi.identity.AppPrincipal;
import app.tabikime.kimetabi.identity.FirebaseTokenVerificationException;
import app.tabikime.kimetabi.identity.FirebaseTokenVerifier;

final class StompFirebaseAuthenticationInterceptor implements ChannelInterceptor {

    static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private final Optional<FirebaseTokenVerifier> tokenVerifier;

    StompFirebaseAuthenticationInterceptor(Optional<FirebaseTokenVerifier> tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }

        String authorization = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER);
        if (authorization == null
                || !authorization.regionMatches(
                        true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new StompAuthenticationException("Firebase ID token is required");
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        if (token.isBlank() || tokenVerifier.isEmpty()) {
            throw new StompAuthenticationException("Firebase ID token is invalid");
        }

        try {
            String uid = tokenVerifier.orElseThrow().verify(token).uid();
            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    new AppPrincipal(uid), null, List.of()));
            return message;
        } catch (FirebaseTokenVerificationException | IllegalArgumentException exception) {
            throw new StompAuthenticationException("Firebase ID token is invalid", exception);
        }
    }

    private static final class StompAuthenticationException extends AuthenticationException {

        private StompAuthenticationException(String message) {
            super(message);
        }

        private StompAuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
