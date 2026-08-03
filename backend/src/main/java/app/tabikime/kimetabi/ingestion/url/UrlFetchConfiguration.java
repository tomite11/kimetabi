package app.tabikime.kimetabi.ingestion.url;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UrlFetchProperties.class)
public class UrlFetchConfiguration {

    @Bean
    AddressResolver addressResolver() {
        return AddressResolver.system();
    }

    @Bean
    UrlAddressValidator urlAddressValidator(AddressResolver resolver) {
        return new UrlAddressValidator(resolver);
    }

    @Bean
    PinnedHttpTransport pinnedHttpTransport() {
        return new SocketPinnedHttpTransport();
    }

    @Bean
    SafeUrlFetcher safeUrlFetcher(
            UrlAddressValidator validator,
            PinnedHttpTransport transport,
            UrlFetchProperties properties
    ) {
        return new SafeUrlFetcher(validator, transport, properties);
    }
}
