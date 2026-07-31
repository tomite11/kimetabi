package app.tabikime.kimetabi.support.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kimetabi.proxy")
public record ProxyProperties(boolean trustGoogleForwardedFor) {
}
