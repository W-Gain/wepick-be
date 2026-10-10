package gguip1.community.global.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;

class SessionCommitterTest {
    @AfterEach
    void clearSecurityContext() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test
    @DisplayName("요청 세션 조회가 실패해도 실패 정리와 쿠키 만료를 시도한다")
    void requestSessionLookupFailureDoesNotEscapeCleanup() {
        var users = mock(gguip1.community.domain.user.repository.UserRepository.class);
        SecurityContextRepository contexts = mock(SecurityContextRepository.class);
        var strategy = mock(SessionAuthenticationStrategy.class);
        @SuppressWarnings("unchecked")
        FindByIndexNameSessionRepository<Session> sessions = mock(FindByIndexNameSessionRepository.class);
        SessionCommitter committer = new SessionCommitter(users, contexts, strategy, sessions, "JSESSIONID", false);
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        var response = new MockHttpServletResponse();
        doThrow(new IllegalStateException("test-only session lookup failure"))
                .when(request).getSession(false);
        doThrow(new IllegalStateException("test-only context save failure"))
                .when(contexts).saveContext(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        assertThatCode(() -> committer.clearAfterFailure(request, response)).doesNotThrowAnyException();
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
                assertThat(cookie).startsWith("JSESSIONID=").contains("Max-Age=0"));
    }

    @Test
    @DisplayName("저장소와 context 정리가 실패해도 실패 응답용 쿠키 만료를 보장한다")
    void repositoryAndContextFailuresDoNotEscapeCleanup() {
        var users = mock(gguip1.community.domain.user.repository.UserRepository.class);
        SecurityContextRepository contexts = mock(SecurityContextRepository.class);
        var strategy = mock(SessionAuthenticationStrategy.class);
        @SuppressWarnings("unchecked")
        FindByIndexNameSessionRepository<Session> sessions = mock(FindByIndexNameSessionRepository.class);
        var session = new MockHttpSession();
        var request = new MockHttpServletRequest();
        request.setSession(session);
        var response = new MockHttpServletResponse();
        doThrow(new IllegalStateException("test-only repository deletion failure"))
                .when(sessions).deleteById(session.getId());
        doThrow(new IllegalStateException("test-only context save failure"))
                .when(contexts).saveContext(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        SessionCommitter committer = new SessionCommitter(users, contexts, strategy, sessions, "JSESSIONID", false);

        assertThatCode(() -> committer.clearAfterFailure(request, response)).doesNotThrowAnyException();
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
                assertThat(cookie).startsWith("JSESSIONID=").contains("Max-Age=0"));
    }

    @Test
    @DisplayName("부분 인증 정리에서 context·세션·저장소와 쿠키를 모두 정리한다")
    void clearsContextInvalidatesSessionAndDeletesPersistedSession() {
        var users = mock(gguip1.community.domain.user.repository.UserRepository.class);
        SecurityContextRepository contexts = mock(SecurityContextRepository.class);
        var strategy = mock(SessionAuthenticationStrategy.class);
        @SuppressWarnings("unchecked")
        FindByIndexNameSessionRepository<Session> sessions = mock(FindByIndexNameSessionRepository.class);
        var session = new MockHttpSession();
        var request = new MockHttpServletRequest();
        request.setSession(session);
        var response = new MockHttpServletResponse();
        String sessionId = session.getId();
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated("42", null, java.util.List.of()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        SessionCommitter committer = new SessionCommitter(users, contexts, strategy, sessions, "JSESSIONID", false);

        committer.clearAfterFailure(request, response);

        assertThat(session.isInvalid()).isTrue();
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(sessions).deleteById(sessionId);
        verify(contexts).saveContext(any(), same(request), same(response));
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
                assertThat(cookie).startsWith("JSESSIONID=").contains("Max-Age=0"));
    }
}
