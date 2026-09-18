package app.tabikime.kimetabi.realtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import app.tabikime.kimetabi.support.config.CorsProperties;
import app.tabikime.kimetabi.trip.TripAuthorizationService;

class WebSocketConfigurationTest {

    @Test
    void allowsOnlyConfiguredOriginsForWebSocketHandshake() {
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration =
                mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint("/ws")).thenReturn(registration);
        CorsProperties corsProperties = new CorsProperties(
                List.of("https://tabikime.app", "https://preview--tabikime.web.app"),
                Duration.ofHours(1));
        WebSocketConfiguration configuration = new WebSocketConfiguration(
                Optional.empty(), mock(TripAuthorizationService.class), corsProperties);

        configuration.registerStompEndpoints(registry);

        verify(registration).setAllowedOrigins(
                "https://tabikime.app", "https://preview--tabikime.web.app");
    }
}
