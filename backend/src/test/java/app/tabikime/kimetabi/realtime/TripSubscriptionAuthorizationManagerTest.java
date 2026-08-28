package app.tabikime.kimetabi.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import app.tabikime.kimetabi.identity.AppPrincipal;
import app.tabikime.kimetabi.trip.TripAuthorizationService;
import app.tabikime.kimetabi.trip.TripNotFoundException;

class TripSubscriptionAuthorizationManagerTest {

    private final TripAuthorizationService tripAuthorization =
            mock(TripAuthorizationService.class);
    private final TripSubscriptionAuthorizationManager authorization =
            new TripSubscriptionAuthorizationManager(tripAuthorization);
    private final UsernamePasswordAuthenticationToken member =
            new UsernamePasswordAuthenticationToken(
                    new AppPrincipal("member-uid"), null, List.of());

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> null).when(tripAuthorization)
                .requireMembership("member-uid", 7);
        doThrow(new TripNotFoundException()).when(tripAuthorization)
                .requireMembership("member-uid", 8);
    }

    @Test
    void permitsOnlyActiveMemberTripTopicSubscription() {
        assertThat(authorization.authorize(() -> member,
                frame(StompCommand.SUBSCRIBE, "/topic/trip/7")).isGranted()).isTrue();
        assertThat(authorization.authorize(() -> member,
                frame(StompCommand.SUBSCRIBE, "/topic/trip/8")).isGranted()).isFalse();
    }

    @Test
    void deniesMalformedDestinationsAndClientSend() {
        assertThat(authorization.authorize(() -> member,
                frame(StompCommand.SUBSCRIBE, "/topic/trip/7/extra")).isGranted()).isFalse();
        assertThat(authorization.authorize(() -> member,
                frame(StompCommand.SUBSCRIBE, "/queue/private")).isGranted()).isFalse();
        assertThat(authorization.authorize(() -> member,
                frame(StompCommand.SEND, "/topic/trip/7")).isGranted()).isFalse();
        assertThat(authorization.authorize(() -> member,
                frame(StompCommand.ACK, null)).isGranted()).isFalse();
    }

    @Test
    void requiresAuthenticationForConnectAndSubscribe() {
        assertThat(authorization.authorize(() -> member,
                frame(StompCommand.CONNECT, null)).isGranted()).isTrue();
        assertThat(authorization.authorize(() -> null,
                frame(StompCommand.CONNECT, null)).isGranted()).isFalse();
        assertThat(authorization.authorize(() -> null,
                frame(StompCommand.SUBSCRIBE, "/topic/trip/7")).isGranted()).isFalse();
    }

    private static Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
