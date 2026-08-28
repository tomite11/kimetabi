package app.tabikime.kimetabi.realtime;

import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;

import app.tabikime.kimetabi.identity.AppPrincipal;
import app.tabikime.kimetabi.trip.TripAuthorizationService;
import app.tabikime.kimetabi.trip.TripNotFoundException;

final class TripSubscriptionAuthorizationManager implements AuthorizationManager<Message<?>> {

    private static final Pattern TRIP_TOPIC = Pattern.compile("^/topic/trip/([1-9][0-9]*)$");
    private final TripAuthorizationService authorization;

    TripSubscriptionAuthorizationManager(TripAuthorizationService authorization) {
        this.authorization = authorization;
    }

    @Override
    public AuthorizationDecision authorize(
            Supplier<? extends Authentication> authenticationSupplier,
            Message<?> message
    ) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        Authentication authentication = authenticationSupplier.get();

        if (command == StompCommand.CONNECT) {
            return decision(isAuthenticated(authentication));
        }
        if (command == StompCommand.SUBSCRIBE) {
            return decision(canSubscribe(authentication, accessor.getDestination()));
        }
        if (command == StompCommand.SEND) {
            return decision(false);
        }
        if (command == null
                || command == StompCommand.UNSUBSCRIBE
                || command == StompCommand.DISCONNECT) {
            return decision(isAuthenticated(authentication));
        }
        return decision(false);
    }

    private boolean canSubscribe(Authentication authentication, String destination) {
        if (!isAuthenticated(authentication)) {
            return false;
        }
        Matcher matcher = destination == null ? null : TRIP_TOPIC.matcher(destination);
        if (matcher == null || !matcher.matches()) {
            return false;
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof AppPrincipal appPrincipal)) {
            return false;
        }
        try {
            authorization.requireMembership(
                    appPrincipal.firebaseUid(), Long.parseLong(matcher.group(1)));
            return true;
        } catch (TripNotFoundException | NumberFormatException exception) {
            return false;
        }
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static AuthorizationDecision decision(boolean granted) {
        return new AuthorizationDecision(granted);
    }
}
