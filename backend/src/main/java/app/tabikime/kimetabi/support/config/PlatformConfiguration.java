package app.tabikime.kimetabi.support.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.tabikime.kimetabi.internal.InternalOidcProperties;
import app.tabikime.kimetabi.async.CloudTasksProperties;
import app.tabikime.kimetabi.storage.ReceiptStorageProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        FirebaseProperties.class,
        CloudTasksProperties.class,
        InternalOidcProperties.class,
        ProxyProperties.class,
        ReceiptStorageProperties.class,
        SecretManagerProperties.class
})
public class PlatformConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
