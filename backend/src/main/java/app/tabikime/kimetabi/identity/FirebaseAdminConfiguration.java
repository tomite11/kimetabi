package app.tabikime.kimetabi.identity;

import java.io.IOException;

import app.tabikime.kimetabi.support.config.FirebaseProperties;
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

    @Bean(destroyMethod = "delete")
    FirebaseApp firebaseApp(FirebaseProperties properties) throws IOException {
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .setProjectId(properties.projectId())
                .build();
        return FirebaseApp.initializeApp(options, APP_NAME);
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
