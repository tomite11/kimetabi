package app.tabikime.kimetabi.internal;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class InternalOidcAuthenticationFilter extends OncePerRequestFilter {

    public static final String TASK_AUTHORITY = "ROLE_INTERNAL_TASKS";
    public static final String SCHEDULER_AUTHORITY = "ROLE_INTERNAL_SCHEDULER";

    private final InternalOidcVerifier verifier;

    public InternalOidcAuthenticationFilter(InternalOidcVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        InternalCaller caller = request.getRequestURI().startsWith("/internal/tasks/")
                ? InternalCaller.CLOUD_TASKS : InternalCaller.CLOUD_SCHEDULER;
        String token = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            unauthorized(response);
            return;
        }
        try {
            verifier.verify(token, caller);
            String authority = caller == InternalCaller.CLOUD_TASKS
                    ? TASK_AUTHORITY : SCHEDULER_AUTHORITY;
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            caller, null, List.of(new SimpleGrantedAuthority(authority))));
            filterChain.doFilter(request, response);
        } catch (InternalOidcVerificationException exception) {
            SecurityContextHolder.clearContext();
            unauthorized(response);
        }
    }

    private static String bearerToken(String authorization) {
        if (authorization == null) return null;
        int separator = authorization.indexOf(' ');
        if (separator < 0
                || !"bearer".equals(authorization.substring(0, separator)
                        .toLowerCase(Locale.ROOT))) {
            return null;
        }
        String token = authorization.substring(separator + 1).trim();
        return token.isEmpty() ? null : token;
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"https://tabikime.app/problems/internal-unauthenticated",\
"title":"Unauthorized","status":401,"code":"INTERNAL_UNAUTHENTICATED",\
"message":"内部サービス認証に失敗しました。"}\
""");
    }
}
