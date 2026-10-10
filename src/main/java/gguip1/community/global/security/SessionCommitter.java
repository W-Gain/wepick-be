package gguip1.community.global.security;

import gguip1.community.domain.auth.identity.MemberLoginResult;
import gguip1.community.domain.user.entity.User;
import gguip1.community.domain.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** DB 병합이 끝난 뒤 ACTIVE 회원 principal을 새 JDBC 세션에 즉시 영속화합니다. */
@Service
public class SessionCommitter {
    private final UserRepository users;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final String cookieName;
    private final boolean secureCookie;

    public SessionCommitter(UserRepository users, SecurityContextRepository contexts,
                            SessionAuthenticationStrategy sessionStrategy,
                            FindByIndexNameSessionRepository<? extends Session> sessions,
                            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String cookieName,
                            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) {
        this.users = Objects.requireNonNull(users, "users");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.sessionStrategy = Objects.requireNonNull(sessionStrategy, "sessionStrategy");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.cookieName = cookieName;
        this.secureCookie = secureCookie;
    }

    @Transactional
    public void commit(MemberLoginResult login, HttpServletRequest request, HttpServletResponse response) {
        User active = users.findActiveForUpdate(login.userId()).orElseThrow(SessionCommitFailure::new);
        AuthenticatedMember principal = new AuthenticatedMember(active.getUserId(), active.getRole());
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));

        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        HttpSession session = request.getSession(true);
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal.getName());
        contexts.saveContext(context, request, response);
        String newSessionId = session.getId();

        Map<String, ? extends Session> indexed = sessions.findByPrincipalName(principal.getName());
        Session persisted = sessions.findById(newSessionId);
        if (persisted == null || !indexed.containsKey(newSessionId)
                || persisted.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null) {
            throw new SessionCommitFailure();
        }
    }

    /** 저장 중 실패하면 남은 servlet context와 세션 cookie를 제거합니다. */
    public void clearAfterFailure(HttpServletRequest request, HttpServletResponse response) {
        try {
            SecurityContextHolder.clearContext();
        } catch (RuntimeException ignored) {
            // 다음 요청에 인증을 남기지 않는 것이 우선이며 응답은 실패 redirect로 끝냅니다.
        }
        try {
            HttpSession partial = request.getSession(false);
            if (partial != null) {
                String partialId = partial.getId();
                try {
                    partial.invalidate();
                } catch (IllegalStateException ignored) {
                    // 이미 무효화된 세션은 저장하지 않습니다.
                }
                try {
                    sessions.deleteById(partialId);
                } catch (RuntimeException ignored) {
                    // Spring Session 저장소가 실패해도 callback은 실패 redirect로 끝냅니다.
                }
            }
        } catch (RuntimeException ignored) {
            // 요청 wrapper의 세션 접근도 실패 callback을 성공으로 바꾸지 않습니다.
        }
        try {
            contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        } catch (RuntimeException ignored) {
            // context 저장 실패는 callback 성공으로 표시하지 않으며 쿠키 만료를 계속 시도합니다.
        }
        try {
            ResponseCookie expired = ResponseCookie.from(cookieName, "")
                    .path("/").httpOnly(true).secure(secureCookie).sameSite("Lax")
                    .maxAge(Duration.ZERO).build();
            response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
        } catch (RuntimeException ignored) {
            // 컨테이너 응답 실패도 callback의 결과 분류를 바꾸지 않습니다.
        }
    }

    public static final class SessionCommitFailure extends RuntimeException {
        public SessionCommitFailure() {
            super("Session could not be persisted");
        }
    }
}
