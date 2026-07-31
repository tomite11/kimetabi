package app.tabikime.kimetabi.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        classes = {
                SecurityConfigurationTest.TestConfiguration.class,
                SecurityConfiguration.class
        },
        properties = "management.endpoints.web.exposure.include=health,info"
)
@AutoConfigureMockMvc
class SecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void deniesApiWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/test/principal"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void convertsVerifiedFirebaseTokenToAppPrincipal() throws Exception {
        mockMvc.perform(get("/api/test/principal")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firebaseUid").value("firebase-user-1"));
    }

    @Test
    void rejectsInvalidFirebaseToken() throws Exception {
        mockMvc.perform(get("/api/test/principal")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void supportsTestPrincipalWithoutCreatingProductionCredentials() throws Exception {
        AppPrincipal principal = new AppPrincipal("fixture-user");
        var authentication =
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of());

        mockMvc.perform(get("/api/test/principal")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firebaseUid").value("fixture-user"));
    }

    @Test
    void exposesHealthWithoutAuthenticationButProtectsOtherActuatorEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void neverAddsTripRolesFromTokenClaims() throws Exception {
        FirebaseTokenVerifier verifier = TestConfiguration.verifier();
        VerifiedFirebaseToken token = verifier.verify("valid-token");

        assertThat(token.uid()).isEqualTo("firebase-user-1");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    static class TestConfiguration {

        @Bean
        FirebaseTokenVerifier firebaseTokenVerifier() {
            return verifier();
        }

        static FirebaseTokenVerifier verifier() {
            return token -> {
                if (!"valid-token".equals(token)) {
                    throw new FirebaseTokenVerificationException("invalid");
                }
                return new VerifiedFirebaseToken("firebase-user-1");
            };
        }

        @Bean
        PrincipalController principalController() {
            return new PrincipalController();
        }
    }

    @RestController
    static class PrincipalController {

        @GetMapping("/api/test/principal")
        AppPrincipal principal(Authentication authentication) {
            return (AppPrincipal) authentication.getPrincipal();
        }
    }
}
