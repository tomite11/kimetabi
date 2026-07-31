package app.tabikime.kimetabi.ingestion.url;

import static app.tabikime.kimetabi.ingestion.url.UrlFetchException.Reason.BLOCKED_ADDRESS;
import static app.tabikime.kimetabi.ingestion.url.UrlFetchException.Reason.INVALID_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlAddressValidatorTest {

    private static final AddressResolver PUBLIC_RESOLVER = resolver(Map.of(
            "public.example", List.of("93.184.216.34"),
            "dual.example", List.of("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946")
    ));

    @Test
    void acceptsOnlyPublicAddressesWhenEveryResolvedAddressIsPublic() throws Exception {
        UrlAddressValidator validator = new UrlAddressValidator(PUBLIC_RESOLVER);

        ValidatedUrl result = validator.validate(URI.create("https://dual.example/path#fragment"));

        assertThat(result.uri().toString()).isEqualTo("https://dual.example/path");
        assertThat(result.port()).isEqualTo(443);
        assertThat(result.addresses()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1",
            "169.254.169.254", "172.16.0.1", "192.168.0.1", "224.0.0.1",
            "192.0.2.1", "192.88.99.1", "198.51.100.1", "203.0.113.1",
            "::", "::1", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1"
    })
    void rejectsNonPublicIpv4AndIpv6(String address) {
        UrlAddressValidator validator = new UrlAddressValidator(
                hostname -> List.of(InetAddress.getByName(address)));

        assertThatThrownBy(() -> validator.validate(URI.create("https://blocked.example")))
                .isInstanceOfSatisfying(
                        UrlFetchException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(BLOCKED_ADDRESS));
    }

    @Test
    void rejectsHostWhenAnyDnsAnswerIsPrivate() {
        UrlAddressValidator validator = new UrlAddressValidator(resolver(Map.of(
                "mixed.example", List.of("93.184.216.34", "10.0.0.1")
        )));

        assertThatThrownBy(() -> validator.validate(URI.create("https://mixed.example")))
                .isInstanceOfSatisfying(
                        UrlFetchException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(BLOCKED_ADDRESS));
    }

    @Test
    void classifiesIpv6LiteralWithoutPassingItToDns() throws Exception {
        AtomicBoolean resolverCalled = new AtomicBoolean();
        UrlAddressValidator validator = new UrlAddressValidator(hostname -> {
            resolverCalled.set(true);
            throw new AssertionError("IP literals must not be sent to DNS");
        });

        ValidatedUrl result =
                validator.validate(URI.create("https://[2606:4700:4700::1111]/"));

        assertThat(resolverCalled).isFalse();
        assertThat(result.addresses()).singleElement()
                .extracting(InetAddress::getHostAddress)
                .isEqualTo("2606:4700:4700:0:0:0:0:1111");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://public.example:22/",
            "ftp://public.example/",
            "https://user:password@public.example/",
            "http://127.1/",
            "http://2130706433/",
            "http://0x7f000001/",
            "http://0177.0.0.1/",
            "https://[2606:4700:4700::1111%25eth0]/"
    })
    void rejectsInvalidSchemePortUserInfoAndAmbiguousIpv4(String url) {
        UrlAddressValidator validator = new UrlAddressValidator(PUBLIC_RESOLVER);

        assertThatThrownBy(() -> validator.validate(URI.create(url)))
                .isInstanceOfSatisfying(
                        UrlFetchException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(INVALID_URL));
    }

    private static AddressResolver resolver(Map<String, List<String>> addresses) {
        return hostname -> {
            List<String> values = addresses.get(hostname);
            if (values == null) {
                throw new java.net.UnknownHostException(hostname);
            }
            return values.stream().map(value -> {
                try {
                    return InetAddress.getByName(value);
                } catch (java.net.UnknownHostException exception) {
                    throw new IllegalArgumentException(exception);
                }
            }).toList();
        };
    }
}
