package app.tabikime.kimetabi.identity;

import java.util.Optional;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import app.tabikime.kimetabi.internal.InternalOidcAuthenticationFilter;
import app.tabikime.kimetabi.internal.InternalOidcVerifier;
import app.tabikime.kimetabi.support.config.CorsProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Optional<FirebaseTokenVerifier> tokenVerifier,
            InternalOidcVerifier internalOidcVerifier,
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/ws").permitAll()
                        .requestMatchers("/internal/tasks/**")
                            .hasAuthority(InternalOidcAuthenticationFilter.TASK_AUTHORITY)
                        .requestMatchers("/internal/outbox/**", "/internal/receipts/**")
                            .hasAuthority(InternalOidcAuthenticationFilter.SCHEDULER_AUTHORITY)
                        .requestMatchers("/actuator/**", "/api/**").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(
                        new InternalOidcAuthenticationFilter(internalOidcVerifier),
                        AnonymousAuthenticationFilter.class)
                .addFilterBefore(
                        new BearerTokenAuthenticationFilter(tokenVerifier),
                        AnonymousAuthenticationFilter.class)
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    response.getWriter().write("""
                            {"type":"https://tabikime.app/problems/unauthenticated",\
"title":"Unauthorized","status":401,"code":"UNAUTHENTICATED",\
"message":"認証が必要です。"}\
""");
                }))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(java.util.List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(java.util.List.of(
                "Authorization", "Content-Type", "Idempotency-Key", "If-Match"));
        configuration.setExposedHeaders(java.util.List.of(
                "Location", "ETag", "Retry-After", "X-Trace-Id"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(properties.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/ws/**", configuration);
        return source;
    }
}
