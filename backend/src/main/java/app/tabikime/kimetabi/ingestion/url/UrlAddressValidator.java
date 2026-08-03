package app.tabikime.kimetabi.ingestion.url;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import app.tabikime.kimetabi.ingestion.url.UrlFetchException.Reason;

public final class UrlAddressValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> BLOCKED_HOSTNAMES =
            Set.of("localhost", "metadata.google.internal");
    private static final Pattern CANONICAL_IPV4 =
            Pattern.compile("(?:0|[1-9]\\d{0,2})(?:\\.(?:0|[1-9]\\d{0,2})){3}");
    private static final Pattern IPV4_LIKE = Pattern.compile("[0-9a-fA-FxX.]+");
    private final AddressResolver resolver;

    public UrlAddressValidator(AddressResolver resolver) {
        this.resolver = resolver;
    }

    public ValidatedUrl validate(URI rawUri) throws UrlFetchException {
        URI uri = normalize(rawUri);
        String hostname = canonicalHostname(uri.getHost());
        if (BLOCKED_HOSTNAMES.contains(hostname)) {
            throw blocked();
        }

        int port = effectivePort(uri);
        if (!UrlFetchProperties.ALLOWED_PORTS.contains(port)) {
            throw new UrlFetchException(Reason.INVALID_URL, "URL port is not allowed");
        }

        List<InetAddress> addresses = resolve(hostname);
        if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublic(address))) {
            throw blocked();
        }
        return new ValidatedUrl(uri, hostname, port, addresses);
    }

    private static String canonicalHostname(String hostname) {
        String normalized = hostname.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private URI normalize(URI uri) throws UrlFetchException {
        if (uri == null || uri.getScheme() == null || uri.getRawAuthority() == null
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new UrlFetchException(Reason.INVALID_URL, "URL must be absolute");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme) || uri.getRawUserInfo() != null) {
            throw new UrlFetchException(Reason.INVALID_URL, "URL scheme or userinfo is invalid");
        }
        rejectAmbiguousIpLiteral(uri.getHost());
        try {
            return new URI(
                    scheme,
                    null,
                    uri.getHost().toLowerCase(Locale.ROOT),
                    uri.getPort(),
                    uri.getRawPath().isEmpty() ? "/" : uri.getRawPath(),
                    uri.getRawQuery(),
                    null);
        } catch (URISyntaxException exception) {
            throw new UrlFetchException(Reason.INVALID_URL, "URL is invalid", exception);
        }
    }

    private void rejectAmbiguousIpLiteral(String hostname) throws UrlFetchException {
        if (hostname.contains(":")) {
            if (hostname.contains("%")) {
                throw new UrlFetchException(Reason.INVALID_URL, "Scoped IPv6 literal is not allowed");
            }
            return;
        }
        if (IPV4_LIKE.matcher(hostname).matches() && !CANONICAL_IPV4.matcher(hostname).matches()) {
            throw new UrlFetchException(Reason.INVALID_URL, "Ambiguous IPv4 literal is not allowed");
        }
        if (CANONICAL_IPV4.matcher(hostname).matches()) {
            for (String octet : hostname.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    throw new UrlFetchException(Reason.INVALID_URL, "IPv4 literal is invalid");
                }
            }
        }
    }

    private List<InetAddress> resolve(String hostname) throws UrlFetchException {
        if (CANONICAL_IPV4.matcher(hostname).matches()) {
            return List.of(parseLiteral(hostname));
        }
        if (hostname.contains(":")) {
            return List.of(parseLiteral(hostname));
        }
        try {
            return resolver.resolve(hostname);
        } catch (UnknownHostException exception) {
            throw new UrlFetchException(Reason.DNS_FAILURE, "Host resolution failed", exception);
        }
    }

    private InetAddress parseLiteral(String literal) throws UrlFetchException {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException exception) {
            throw new UrlFetchException(Reason.INVALID_URL, "IP literal is invalid", exception);
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equals(uri.getScheme()) ? 443 : 80;
    }

    static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicIpv4(bytes);
        }
        if (address instanceof Inet6Address) {
            return isPublicIpv6(bytes);
        }
        return false;
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        return first != 0
                && first != 10
                && first != 127
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && second == 0)
                && !(first == 192 && second == 88 && unsigned(bytes[2]) == 99)
                && !(first == 192 && second == 168)
                && !(first == 198 && (second == 18 || second == 19))
                && !(first == 198 && second == 51 && unsigned(bytes[2]) == 100)
                && !(first == 203 && second == 0 && unsigned(bytes[2]) == 113)
                && first < 224
                && first != 240
                && first != 255;
    }

    private static boolean isPublicIpv6(byte[] bytes) {
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        boolean globalUnicast = (first & 0xe0) == 0x20;
        boolean documentation = first == 0x20 && second == 0x01
                && unsigned(bytes[2]) == 0x0d && unsigned(bytes[3]) == 0xb8;
        return globalUnicast && !documentation;
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static UrlFetchException blocked() {
        return new UrlFetchException(Reason.BLOCKED_ADDRESS, "URL address is not public");
    }
}
