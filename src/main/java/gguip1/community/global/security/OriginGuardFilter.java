package gguip1.community.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/** 모든 변경 요청은 고정된 설정 Origin과 정확히 일치해야 합니다. */
public final class OriginGuardFilter extends OncePerRequestFilter {
    private final SecurityProperties properties;
    public OriginGuardFilter(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        if (HttpMethod.POST.matches(method) || HttpMethod.PUT.matches(method)
                || HttpMethod.PATCH.matches(method) || HttpMethod.DELETE.matches(method)) {
            var origins = Collections.list(request.getHeaders("Origin"));
            if (origins.size() != 1 || !properties.validatedAllowedOrigin().equals(origins.getFirst())) {
                ApiSecurityErrorWriter.write(response, 403, "CSRF_INVALID", "요청 보호 정보를 확인할 수 없습니다.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
