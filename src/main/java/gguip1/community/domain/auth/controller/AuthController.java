package gguip1.community.domain.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** WePick 세션만 종료하고 익명 표 식별은 유지합니다. */
@RestController
public class AuthController {
    private final CsrfTokenRepository csrfTokens;
    private final String sessionCookieName;
    private final boolean secureCookie;

    public AuthController(
            CsrfTokenRepository csrfTokens,
            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) {
        this.csrfTokens = csrfTokens;
        this.sessionCookieName = sessionCookieName;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        csrfTokens.saveToken(null, request, response);
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        ResponseCookie expired = ResponseCookie.from(sessionCookieName, "")
                .path("/")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
    }
}
