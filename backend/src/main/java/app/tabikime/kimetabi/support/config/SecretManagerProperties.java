package app.tabikime.kimetabi.support.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kimetabi.secrets")
public record SecretManagerProperties(String projectId) {
}
