package app.tabikime.kimetabi.internal;

import com.google.auth.oauth2.TokenVerifier;
import com.google.auth.oauth2.TokenVerifier.VerificationException;
import com.google.api.client.json.webtoken.JsonWebSignature;
import org.springframework.stereotype.Component;

@Component
final class GoogleInternalOidcVerifier implements InternalOidcVerifier {

    private final InternalOidcProperties properties;
    private final TokenVerifier tasksVerifier;
    private final TokenVerifier schedulerVerifier;

    GoogleInternalOidcVerifier(InternalOidcProperties properties) {
        this.properties = properties;
        this.tasksVerifier = build(properties.tasks());
        this.schedulerVerifier = build(properties.scheduler());
    }

    @Override
    public void verify(String token, InternalCaller caller)
            throws InternalOidcVerificationException {
        InternalOidcProperties.Identity identity = caller == InternalCaller.CLOUD_TASKS
                ? properties.tasks() : properties.scheduler();
        if (identity == null || !identity.configured()) {
            throw new InternalOidcVerificationException("Internal OIDC identity is not configured");
        }
        try {
            TokenVerifier tokenVerifier = caller == InternalCaller.CLOUD_TASKS
                    ? tasksVerifier : schedulerVerifier;
            JsonWebSignature verified = tokenVerifier.verify(token);
            Object email = verified.getPayload().get("email");
            Object emailVerified = verified.getPayload().get("email_verified");
            if (!identity.serviceAccountEmail().equals(email)
                    || !Boolean.parseBoolean(String.valueOf(emailVerified))) {
                throw new InternalOidcVerificationException(
                        "Internal OIDC service account is not allowed");
            }
        } catch (VerificationException exception) {
            throw new InternalOidcVerificationException("Internal OIDC token is invalid", exception);
        }
    }

    private static TokenVerifier build(InternalOidcProperties.Identity identity) {
        if (identity == null || !identity.configured()) return null;
        return TokenVerifier.newBuilder()
                .setAudience(identity.audience())
                .setIssuer("https://accounts.google.com")
                .build();
    }
}
