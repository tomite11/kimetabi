package app.tabikime.kimetabi.support.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.tabikime.kimetabi.internal.InternalOidcProperties;
import app.tabikime.kimetabi.async.CloudTasksProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        FirebaseProperties.class,
        CloudTasksProperties.class,
        InternalOidcProperties.class,
        ProxyProperties.class,
        SecretManagerProperties.class
})
public class PlatformConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
