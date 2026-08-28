package app.tabikime.kimetabi.realtime;

import java.util.Optional;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import app.tabikime.kimetabi.identity.FirebaseTokenVerifier;
import app.tabikime.kimetabi.trip.TripAuthorizationService;

@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class WebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

    private final Optional<FirebaseTokenVerifier> tokenVerifier;
    private final TripSubscriptionAuthorizationManager subscriptionAuthorization;

    public WebSocketConfiguration(
            Optional<FirebaseTokenVerifier> tokenVerifier,
            TripAuthorizationService tripAuthorization
    ) {
        this.tokenVerifier = tokenVerifier;
        this.subscriptionAuthorization =
                new TripSubscriptionAuthorizationManager(tripAuthorization);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                new StompFirebaseAuthenticationInterceptor(tokenVerifier),
                new SecurityContextChannelInterceptor(),
                new AuthorizationChannelInterceptor(subscriptionAuthorization));
    }
}
