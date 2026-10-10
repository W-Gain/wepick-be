package gguip1.community.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.http.HttpMethod;

import java.io.IOException;

public final class ApiAccessDeniedHandler implements AccessDeniedHandler {
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException failure)
            throws IOException {
        if (failure instanceof MissingCsrfTokenException || failure instanceof InvalidCsrfTokenException) {
            ApiSecurityErrorWriter.write(response, 403, "CSRF_INVALID", "요청 보호 정보를 확인할 수 없습니다.");
            return;
        }
        if ((HttpMethod.POST.matches(request.getMethod()) && "/topics".equals(request.getServletPath()))
                || (HttpMethod.PATCH.matches(request.getMethod())
                && request.getServletPath().matches("/topics/[^/]+"))) {
            ApiSecurityErrorWriter.write(response, 403, "ADMIN_REQUIRED", "관리자 권한이 필요합니다.");
            return;
        }
        ApiSecurityErrorWriter.write(response, 403, "ACCESS_DENIED", "요청을 수행할 권한이 없습니다.");
    }
}
