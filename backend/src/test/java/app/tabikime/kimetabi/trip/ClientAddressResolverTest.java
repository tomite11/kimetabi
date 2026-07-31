package app.tabikime.kimetabi.trip;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import app.tabikime.kimetabi.support.config.ProxyProperties;

class ClientAddressResolverTest {

    @Test
    void ignoresForwardedHeaderOutsideTrustedGoogleProxyEnvironment() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 203.0.113.5, 35.1.2.3");

        ClientAddressResolver resolver =
                new ClientAddressResolver(new ProxyProperties(false));

        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.10");
    }

    @Test
    void usesGoogleObservedClientInsteadOfSpoofablePrefix() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("169.254.1.1");
        request.addHeader(
                "X-Forwarded-For",
                "attacker-value, 198.51.100.20, 35.1.2.3");

        ClientAddressResolver resolver =
                new ClientAddressResolver(new ProxyProperties(true));

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void canonicalizesIpv6Address() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("2001:db8:0:0:0:0:0:1");

        ClientAddressResolver resolver =
                new ClientAddressResolver(new ProxyProperties(false));

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void doesNotResolveHostnameLikeForwardedValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "dead, 35.1.2.3");

        ClientAddressResolver resolver =
                new ClientAddressResolver(new ProxyProperties(true));

        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.10");
    }
}
