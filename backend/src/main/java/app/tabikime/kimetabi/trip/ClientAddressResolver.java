package app.tabikime.kimetabi.trip;

import java.net.InetAddress;
import java.net.UnknownHostException;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import app.tabikime.kimetabi.support.config.ProxyProperties;

@Component
public class ClientAddressResolver {

    private final ProxyProperties properties;

    public ClientAddressResolver(ProxyProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (properties.trustGoogleForwardedFor() && forwardedFor != null) {
            String[] addresses = forwardedFor.split(",", -1);
            if (addresses.length >= 2) {
                String loadBalancerObservedClient = addresses[addresses.length - 2].trim();
                if (isNumericAddress(loadBalancerObservedClient)) {
                    return canonicalAddress(loadBalancerObservedClient);
                }
            }
        }
        String remoteAddress = request.getRemoteAddr();
        return isNumericAddress(remoteAddress) ? canonicalAddress(remoteAddress) : "unknown";
    }

    private boolean isNumericAddress(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 45
                && (value.indexOf('.') >= 0 || value.indexOf(':') >= 0)
                && value.chars().allMatch(character ->
                        Character.digit(character, 16) >= 0
                                || character == '.'
                                || character == ':');
    }

    private String canonicalAddress(String value) {
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException exception) {
            return "unknown";
        }
    }
}
