package app.tabikime.kimetabi.support.web;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String RESPONSE_HEADER = "X-Trace-Id";
    static final String REQUEST_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    private static final String MDC_KEY = "traceId";
    private static final int TRACE_ID_BYTES = 16;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = newTraceId();
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        response.setHeader(RESPONSE_HEADER, traceId);

        try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, traceId)) {
            filterChain.doFilter(request, response);
        }
    }

    private String newTraceId() {
        byte[] bytes = new byte[TRACE_ID_BYTES];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
