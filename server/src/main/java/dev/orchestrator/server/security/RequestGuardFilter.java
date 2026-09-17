package dev.orchestrator.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies {@link RequestGuard} to every {@code /api/**} request. */
@Component
public class RequestGuardFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestGuardFilter.class);

    private final RequestGuard guard;

    public RequestGuardFilter(ApiToken token) {
        this.guard = new RequestGuard(token.value());
        log.info("API guard: loopback host only, cross-site writes blocked, token {}", guard.tokenRequired() ? "required" : "not required");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RequestGuard.Verdict verdict = guard.evaluate(new RequestGuard.Request(
                request.getMethod(), request.getRequestURI(), request.getHeader("Host"), request.getHeader("Origin"),
                request.getHeader("X-Orchestrator-Token"), request.getHeader("Authorization"), request.getParameter("token")));
        switch (verdict) {
            case ALLOW -> chain.doFilter(request, response);
            case FORBIDDEN_HOST -> reject(response, 403, "loopback 주소(localhost)로만 접근할 수 있습니다 (Host: " + request.getHeader("Host") + ")");
            case FORBIDDEN_ORIGIN -> reject(response, 403, "다른 출처(" + request.getHeader("Origin") + ")에서는 변경 요청을 보낼 수 없습니다");
            case MISSING_TOKEN -> reject(response, 401, "API 토큰이 필요합니다 (X-Orchestrator-Token 헤더, 값은 <data-dir>/api-token)");
        }
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
