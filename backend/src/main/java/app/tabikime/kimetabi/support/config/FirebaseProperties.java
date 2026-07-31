package app.tabikime.kimetabi.support.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kimetabi.firebase")
public record FirebaseProperties(String projectId) {
}
