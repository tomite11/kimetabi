package app.tabikime.kimetabi.identity;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import app.tabikime.kimetabi.support.config.FirebaseProperties;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@Conditional(FirebaseAdminConfiguration.FirebaseProjectConfiguredCondition.class)
class FirebaseAdminConfiguration {

    private static final String APP_NAME = "kimetabi-auth";
    private static final String AUTH_EMULATOR_HOST = "FIREBASE_AUTH_EMULATOR_HOST";

    @Bean(destroyMethod = "delete")
    FirebaseApp firebaseApp(FirebaseProperties properties) throws IOException {
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials(
                        properties.projectId(), System.getenv(AUTH_EMULATOR_HOST)))
                .setProjectId(properties.projectId())
                .build();
        return FirebaseApp.initializeApp(options, APP_NAME);
    }

    static GoogleCredentials credentials(String projectId, String emulatorHost)
            throws IOException {
        if (emulatorHost == null || emulatorHost.isBlank()) {
            return GoogleCredentials.getApplicationDefault();
        }
        if (!projectId.startsWith("demo-")) {
            throw new IllegalStateException(
                    "Firebase Auth Emulator requires a demo- project ID");
        }
        return GoogleCredentials.create(new AccessToken(
                "firebase-auth-emulator",
                Date.from(Instant.now().plus(1, ChronoUnit.HOURS))));
    }

    @Bean
    FirebaseAuth firebaseAuth(FirebaseApp firebaseApp) {
        return FirebaseAuth.getInstance(firebaseApp);
    }

    @Bean
    FirebaseTokenVerifier firebaseTokenVerifier(FirebaseAuth firebaseAuth) {
        return new FirebaseAdminTokenVerifier(firebaseAuth);
    }

    static final class FirebaseProjectConfiguredCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(
                    context.getEnvironment().getProperty("kimetabi.firebase.project-id"));
        }
    }
}
