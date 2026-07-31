package app.tabikime.kimetabi.support.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        FirebaseProperties.class,
        SecretManagerProperties.class
})
public class PlatformConfiguration {
}
