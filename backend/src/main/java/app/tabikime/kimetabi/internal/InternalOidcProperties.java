package app.tabikime.kimetabi.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kimetabi.internal-oidc")
public record InternalOidcProperties(
        Identity tasks,
        Identity scheduler
) {

    public record Identity(String audience, String serviceAccountEmail) {

        boolean configured() {
            return audience != null && !audience.isBlank()
                    && serviceAccountEmail != null && !serviceAccountEmail.isBlank();
        }
    }
}
