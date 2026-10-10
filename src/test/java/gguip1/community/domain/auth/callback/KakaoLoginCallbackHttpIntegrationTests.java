package gguip1.community.domain.auth.callback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gguip1.community.domain.auth.identity.AnonymousVoterCookie;
import gguip1.community.domain.auth.identity.KakaoIdentity;
import gguip1.community.domain.auth.identity.KakaoIdentityClient;
import gguip1.community.global.security.AuthenticatedMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 Tomcat callback, Spring Session JDBC, CSRF/Origin, MySQL 병합을 함께 검증합니다. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.kakao.enabled=true",
        "app.auth.kakao.client-id=test-only-client",
        "app.auth.kakao.client-secret=test-only-secret",
        "app.auth.kakao.redirect-uri=http://127.0.0.1:8080/auth/kakao/callback",
        "app.auth.kakao.cookie-secure=false",
        "app.auth.kakao.allow-insecure-loopback-cookie=true",
        "app.auth.anonymous-voter.secure=false",
        "app.security.allowed-origin=http://127.0.0.1:5173",
        "cors.allowed-origins=http://127.0.0.1:5173",
        "server.servlet.session.cookie.secure=false"
})
@Import(KakaoLoginCallbackHttpIntegrationTests.FakeIdentityConfiguration.class)
class KakaoLoginCallbackHttpIntegrationTests {
    private static final String BINDING_COOKIE = "wepick-login-binding-local";
    private static final String SESSION_COOKIE = "JSESSIONID";
    private static final String ORIGIN = "http://127.0.0.1:5173";
    private static final Pattern QUERY_VALUE = Pattern.compile("(?:^|&)state=([^&]+)");
    private static final AtomicLong IDS = new AtomicLong(8_500_000_000L);

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeIdentityClient identityClient;
    @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;

    @BeforeEach
    void resetTestDatabaseAndFakeProvider() {
        jdbc.update("DELETE FROM SPRING_SESSION");
        jdbc.update("DELETE FROM login_attempts");
        jdbc.update("DELETE FROM votes");
        jdbc.update("DELETE FROM anonymous_voters");
        jdbc.update("DELETE FROM social_accounts");
        jdbc.update("DELETE FROM topic_options");
        jdbc.update("DELETE FROM topics");
        jdbc.update("DELETE FROM users");
        identityClient.reset();
    }

    @Test
    @DisplayName("정상 callback이 회원과 세션을 만들고 /me에서 읽는다")
    void callbackCreatesMemberAndPersistsOnlyInternalPrincipal() throws Exception {
        HttpResponse<String> anonymous = get("/me", null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(json.readTree(anonymous.body()).at("/error/code").asText()).isEqualTo("UNAUTHENTICATED");

        String binding = prepareBinding();
        long providerId = IDS.incrementAndGet();
        BrowserAttempt attempt = beginAttempt(binding, null, "callback-created-" + providerId, "/feed");
        HttpResponse<String> callback = callback(attempt, "member-" + providerId, null);

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location").orElseThrow())
                .contains("/auth/complete", "result=success");
        assertThat(callback.body()).isEmpty();
        assertThat(callback.headers().firstValue("Cache-Control")).contains("private, no-store");
        assertThat(callback.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(identityClient.calls()).isEqualTo(1);
        assertThat(identityClient.allCallsWereOutsideTransactions()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_accounts WHERE provider_user_id = ?",
                Integer.class, Long.toString(providerId))).isEqualTo(1);
        Map<String, Object> user = jdbc.queryForMap("SELECT nickname, profile_image_id FROM users");
        assertThat((String) user.get("nickname")).matches("위픽-[a-z2-7]{16}");
        assertThat(user.get("profile_image_id")).isNull();
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'", String.class))
                .doesNotContain("email", "password");

        String newSessionCookie = sessionCookie(callback);
        assertThat(newSessionCookie).startsWith(SESSION_COOKIE + "=");
        assertThat(newSessionCookie).isNotEqualTo(attempt.sessionCookie());
        HttpResponse<String> me = get("/me", newSessionCookie);
        assertThat(me.statusCode()).isEqualTo(200);
        JsonNode memberEnvelope = json.readTree(me.body());
        assertThat(memberEnvelope.has("message")).isFalse();
        assertThat(memberEnvelope.has("error")).isFalse();
        assertThat(me.headers().firstValue("Cache-Control")).contains("private, no-store");
        JsonNode member = memberEnvelope.path("data");
        assertThat(member.path("id").asLong()).isPositive();
        assertThat(member.path("nickname").asText()).matches("위픽-[a-z2-7]{16}");
        assertThat(member.has("email")).isFalse();
        assertThat(member.has("password")).isFalse();
        HttpResponse<String> legacyMe = get("/users/me", newSessionCookie);
        assertThat(legacyMe.statusCode()).isEqualTo(200);
        assertThat(json.readTree(legacyMe.body()).path("data").has("email")).isFalse();

        Long userId = member.path("id").asLong();
        Map<String, ? extends Session> indexed = sessions.findByPrincipalName(Long.toString(userId));
        assertThat(indexed).containsKey(sessionId(newSessionCookie));
        Session persisted = sessions.findById(sessionId(newSessionCookie));
        assertThat(persisted).isNotNull();
        Object stored = persisted.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(stored).isInstanceOf(SecurityContext.class);
        Authentication authentication = ((SecurityContext) stored).getAuthentication();
        assertThat(authentication.getPrincipal()).isInstanceOf(AuthenticatedMember.class);
        AuthenticatedMember principal = (AuthenticatedMember) authentication.getPrincipal();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.toString()).doesNotContain(Long.toString(providerId));
        assertThat(java.util.Arrays.stream(principal.getClass().getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getName).toList())
                .containsExactlyInAnyOrder("userId", "role");

        HttpResponse<String> replay = callback(attempt, "member-" + providerId, null);
        assertThat(replay.statusCode()).isEqualTo(302);
        assertThat(replay.headers().firstValue("Location").orElseThrow()).contains("result=failed");
        assertThat(identityClient.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("회원 표를 우선하고 익명 표를 원자적으로 병합한다")
    void callbackMergesAnonymousVotesWithMemberPriority() throws Exception {
        long providerId = IDS.incrementAndGet();
        long memberId = insertUser("existing-member");
        jdbc.update("INSERT INTO social_accounts (user_id, provider, provider_user_id, connected_at) VALUES (?, 'KAKAO', ?, NOW(6))",
                memberId, Long.toString(providerId));

        long firstTopic = insertTopic("member wins");
        long memberOption = insertOption(firstTopic, "A", 1);
        long duplicateAnonymousOption = insertOption(firstTopic, "B", 1);
        jdbc.update("INSERT INTO votes (user_id, topic_id, option_id) VALUES (?, ?, ?)", memberId, firstTopic, memberOption);

        long secondTopic = insertTopic("anonymous moves");
        long movedAnonymousOption = insertOption(secondTopic, "A", 1);
        String anonymousToken = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String tokenHash = AnonymousVoterCookie.hashIfValid(anonymousToken);
        long anonymousId = insertAnonymousVoter(tokenHash);
        jdbc.update("INSERT INTO votes (user_id, anonymous_voter_id, topic_id, option_id) VALUES (NULL, ?, ?, ?)",
                anonymousId, firstTopic, duplicateAnonymousOption);
        jdbc.update("INSERT INTO votes (user_id, anonymous_voter_id, topic_id, option_id) VALUES (NULL, ?, ?, ?)",
                anonymousId, secondTopic, movedAnonymousOption);
        Map<String, Object> movedVoteBefore = jdbc.queryForMap(
                "SELECT vote_id, option_id, created_at FROM votes WHERE topic_id = ?", secondTopic);

        String binding = prepareBinding();
        BrowserAttempt attempt = beginAttempt(binding, null, "callback-merge-" + providerId, "/feed");
        HttpResponse<String> callback = callback(attempt, "member-" + providerId,
                AnonymousVoterCookie.LOCAL_NAME + "=" + anonymousToken);

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location").orElseThrow()).contains("merge=kept_member_vote");
        assertThat(jdbc.queryForObject("SELECT user_id FROM votes WHERE topic_id = ?", Long.class, firstTopic))
                .isEqualTo(memberId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM votes WHERE topic_id = ?", Integer.class, firstTopic))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_id FROM votes WHERE topic_id = ?", Long.class, secondTopic))
                .isEqualTo(memberId);
        assertThat(jdbc.queryForObject("SELECT anonymous_voter_id FROM votes WHERE topic_id = ?", Long.class, secondTopic))
                .isNull();
        Map<String, Object> movedVoteAfter = jdbc.queryForMap(
                "SELECT vote_id, option_id, created_at FROM votes WHERE topic_id = ?", secondTopic);
        assertThat(movedVoteAfter).isEqualTo(movedVoteBefore);
        assertThat(jdbc.queryForObject("SELECT vote_count FROM topic_options WHERE option_id = ?", Long.class,
                duplicateAnonymousOption)).isZero();
        assertThat(jdbc.queryForObject("SELECT linked_user_id FROM anonymous_voters WHERE anonymous_voter_id = ?",
                Long.class, anonymousId)).isEqualTo(memberId);
    }

    @Test
    @DisplayName("타회원 표를 보존하고 유효 익명 표만 이관하며 만료 표는 건너뛴다")
    void callbackPreservesForeignMemberVoteAndMergesCurrentIdentityButSkipsExpiredVote() throws Exception {
        long providerId = IDS.incrementAndGet();
        long memberId = insertUser("merge-owner");
        long otherMemberId = insertUser("other-owner");
        jdbc.update("INSERT INTO social_accounts (user_id, provider, provider_user_id, connected_at) VALUES (?, 'KAKAO', ?, NOW(6))",
                memberId, Long.toString(providerId));

        long foreignTopic = insertTopic("foreign linked vote");
        long foreignMemberOption = insertOption(foreignTopic, "A", 1);
        long foreignAnonymousOption = insertOption(foreignTopic, "B", 1);
        String foreignToken = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        long foreignAnonymousId = insertAnonymousVoter(AnonymousVoterCookie.hashIfValid(foreignToken));
        jdbc.update("UPDATE anonymous_voters SET linked_user_id = ? WHERE anonymous_voter_id = ?",
                otherMemberId, foreignAnonymousId);
        jdbc.update("INSERT INTO votes (user_id, topic_id, option_id) VALUES (?, ?, ?)",
                otherMemberId, foreignTopic, foreignMemberOption);
        jdbc.update("INSERT INTO votes (user_id, anonymous_voter_id, topic_id, option_id) VALUES (NULL, ?, ?, ?)",
                foreignAnonymousId, foreignTopic, foreignAnonymousOption);
        Map<String, Object> previousMemberVote = jdbc.queryForMap(
                "SELECT vote_id, user_id, anonymous_voter_id, option_id, created_at FROM votes "
                        + "WHERE user_id = ? AND topic_id = ?", otherMemberId, foreignTopic);
        long anonymousVoteId = jdbc.queryForObject(
                "SELECT vote_id FROM votes WHERE anonymous_voter_id = ? AND topic_id = ?", Long.class,
                foreignAnonymousId, foreignTopic);
        Object anonymousVoteCreatedAt = jdbc.queryForObject(
                "SELECT created_at FROM votes WHERE vote_id = ?", Object.class, anonymousVoteId);

        long expiredTopic = insertTopic("expired anonymous vote");
        long expiredOption = insertOption(expiredTopic, "B", 1);
        String expiredToken = anonymousToken(1);
        long expiredAnonymousId = insertAnonymousVoter(AnonymousVoterCookie.hashIfValid(expiredToken));
        jdbc.update("UPDATE anonymous_voters SET expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                + "WHERE anonymous_voter_id = ?", expiredAnonymousId);
        jdbc.update("INSERT INTO votes (user_id, anonymous_voter_id, topic_id, option_id) VALUES (NULL, ?, ?, ?)",
                expiredAnonymousId, expiredTopic, expiredOption);

        String binding = prepareBinding();
        BrowserAttempt foreignAttempt = beginAttempt(binding, null, "callback-foreign-" + providerId, "/feed");
        HttpResponse<String> foreignCallback = callback(foreignAttempt, "member-" + providerId,
                AnonymousVoterCookie.LOCAL_NAME + "=" + foreignToken);
        assertThat(foreignCallback.headers().firstValue("Location").orElseThrow()).contains("result=success")
                .doesNotContain("merge=");
        assertThat(jdbc.queryForObject("SELECT linked_user_id FROM anonymous_voters WHERE anonymous_voter_id = ?",
                Long.class, foreignAnonymousId)).isEqualTo(memberId);
        assertThat(jdbc.queryForMap("SELECT vote_id, user_id, anonymous_voter_id, option_id, created_at "
                + "FROM votes WHERE vote_id = ?", previousMemberVote.get("vote_id"))).isEqualTo(previousMemberVote);
        Map<String, Object> movedForeignAnonymousVote = jdbc.queryForMap(
                "SELECT vote_id, user_id, anonymous_voter_id, option_id, created_at FROM votes WHERE vote_id = ?",
                anonymousVoteId);
        assertThat(movedForeignAnonymousVote.get("vote_id")).isEqualTo(anonymousVoteId);
        assertThat(movedForeignAnonymousVote.get("user_id")).isEqualTo(memberId);
        assertThat(movedForeignAnonymousVote.get("anonymous_voter_id")).isNull();
        assertThat(movedForeignAnonymousVote.get("option_id")).isEqualTo(foreignAnonymousOption);
        assertThat(movedForeignAnonymousVote.get("created_at")).isEqualTo(anonymousVoteCreatedAt);
        assertThat(jdbc.queryForObject("SELECT vote_count FROM topic_options WHERE option_id = ?", Long.class,
                foreignMemberOption)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT vote_count FROM topic_options WHERE option_id = ?", Long.class,
                foreignAnonymousOption)).isEqualTo(1L);

        BrowserAttempt expiredAttempt = beginAttempt(binding, null, "callback-expired-" + providerId, "/feed");
        HttpResponse<String> expiredCallback = callback(expiredAttempt, "member-" + providerId,
                AnonymousVoterCookie.LOCAL_NAME + "=" + expiredToken);
        assertThat(expiredCallback.headers().firstValue("Location").orElseThrow()).contains("result=success")
                .doesNotContain("merge=");
        assertThat(jdbc.queryForObject("SELECT linked_user_id FROM anonymous_voters WHERE anonymous_voter_id = ?",
                Long.class, expiredAnonymousId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM votes WHERE topic_id = ? AND user_id IS NULL "
                + "AND anonymous_voter_id = ?", Integer.class, expiredTopic, expiredAnonymousId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT vote_count FROM topic_options WHERE option_id = ?", Long.class,
                expiredOption)).isEqualTo(1L);
    }

    @Test
    @DisplayName("병합 중 실패하면 신규 회원·연결·표·카운터·익명 링크를 모두 되돌린다")
    void callbackRollsBackNewMemberAndEveryMergeMutationOnFailure() throws Exception {
        long providerId = IDS.incrementAndGet();
        long topicId = insertTopic("merge rollback");
        long optionId = insertOption(topicId, "A", 1);
        String anonymousToken = anonymousToken(9);
        long anonymousId = insertAnonymousVoter(AnonymousVoterCookie.hashIfValid(anonymousToken));
        jdbc.update("INSERT INTO votes (user_id, anonymous_voter_id, topic_id, option_id) VALUES (NULL, ?, ?, ?)",
                anonymousId, topicId, optionId);
        Map<String, Object> originalVote = jdbc.queryForMap(
                "SELECT vote_id, user_id, anonymous_voter_id, option_id, created_at FROM votes WHERE topic_id = ?", topicId);

        jdbc.execute("ALTER TABLE votes ADD CONSTRAINT CHK_test_merge_failure CHECK (user_id IS NULL)");
        HttpResponse<String> callback;
        try {
            String binding = prepareBinding();
            BrowserAttempt attempt = beginAttempt(binding, null, "callback-rollback-" + providerId, "/feed");
            callback = callback(attempt, "member-" + providerId,
                    AnonymousVoterCookie.LOCAL_NAME + "=" + anonymousToken);
        } finally {
            jdbc.execute("ALTER TABLE votes DROP CHECK CHK_test_merge_failure");
        }

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location").orElseThrow()).contains("result=failed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_accounts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM votes WHERE topic_id = ?", Integer.class, topicId)).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                "SELECT vote_id, user_id, anonymous_voter_id, option_id, created_at FROM votes WHERE topic_id = ?", topicId))
                .isEqualTo(originalVote);
        assertThat(jdbc.queryForObject("SELECT vote_count FROM topic_options WHERE option_id = ?", Long.class, optionId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT linked_user_id FROM anonymous_voters WHERE anonymous_voter_id = ?",
                Long.class, anonymousId)).isNull();
    }

    @Test
    @DisplayName("중복·형식 오류 익명 쿠키는 표 병합 근거로 쓰지 않는다")
    void duplicateOrMalformedAnonymousCookieCannotMergeVotes() throws Exception {
        long providerId = IDS.incrementAndGet();
        long topicId = insertTopic("anonymous cookie evidence");
        long optionId = insertOption(topicId, "A", 1);
        String validToken = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        long anonymousId = insertAnonymousVoter(AnonymousVoterCookie.hashIfValid(validToken));
        jdbc.update("INSERT INTO votes (user_id, anonymous_voter_id, topic_id, option_id) VALUES (NULL, ?, ?, ?)",
                anonymousId, topicId, optionId);

        String binding = prepareBinding();
        List<String> invalidEvidence = List.of(
                AnonymousVoterCookie.LOCAL_NAME + "=" + validToken + "; "
                        + AnonymousVoterCookie.LOCAL_NAME + "=" + validToken,
                AnonymousVoterCookie.LOCAL_NAME + "=malformed");
        for (int index = 0; index < invalidEvidence.size(); index++) {
            BrowserAttempt attempt = beginAttempt(binding, null,
                    "callback-cookie-" + providerId + "-" + index, "/feed");
            HttpResponse<String> callback = callback(attempt, "member-" + providerId, invalidEvidence.get(index));
            assertThat(callback.statusCode()).isEqualTo(302);
            assertThat(callback.headers().firstValue("Location").orElseThrow()).doesNotContain("merge=");
        }

        assertThat(jdbc.queryForObject("SELECT linked_user_id FROM anonymous_voters WHERE anonymous_voter_id = ?",
                Long.class, anonymousId)).isNull();
        assertThat(jdbc.queryForObject("SELECT user_id FROM votes WHERE topic_id = ?", Long.class, topicId)).isNull();
        assertThat(jdbc.queryForObject("SELECT anonymous_voter_id FROM votes WHERE topic_id = ?", Long.class, topicId))
                .isEqualTo(anonymousId);
        assertThat(jdbc.queryForObject("SELECT vote_count FROM topic_options WHERE option_id = ?", Long.class, optionId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("Origin·CSRF 거부 봉투와 로그아웃 쿠키를 고정한다")
    void csrfOriginAndLogoutContract() throws Exception {
        CsrfSession csrfSession = csrfSession();
        HttpResponse<String> missingOrigin = postLogout(csrfSession.cookie(), ORIGIN, csrfSession.token(), false);
        HttpResponse<String> wrongOrigin = postLogout(csrfSession.cookie(), "http://evil.example", csrfSession.token(), true);
        HttpResponse<String> missingToken = postLogout(csrfSession.cookie(), ORIGIN, null, true);
        assertThat(missingOrigin.statusCode()).isEqualTo(403);
        assertThat(wrongOrigin.statusCode()).isEqualTo(403);
        assertThat(missingToken.statusCode()).isEqualTo(403);
        assertThat(json.readTree(missingOrigin.body()).at("/error/code").asText()).isEqualTo("CSRF_INVALID");
        assertThat(wrongOrigin.body()).isEqualTo(missingOrigin.body());
        assertThat(missingToken.body()).isEqualTo(missingOrigin.body());

        HttpResponse<String> logout = postLogout(csrfSession.cookie(), ORIGIN, csrfSession.token(), true);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().firstValue("Cache-Control")).contains("private, no-store");
        assertThat(logout.headers().allValues("Set-Cookie"))
                .anySatisfy(value -> assertThat(value).startsWith(SESSION_COOKIE + "=").contains("Max-Age=0"));
        assertThat(get("/me", csrfSession.cookie()).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("로그인 세션 교체는 이전 CSRF 토큰을 폐기하고 새 토큰으로 로그아웃한다")
    void loginInvalidatesPreAuthenticationCsrfToken() throws Exception {
        CsrfSession beforeLogin = csrfSession();
        String binding = prepareBinding();
        long providerId = IDS.incrementAndGet();
        BrowserAttempt attempt = beginAttempt(binding, beforeLogin.cookie(),
                "callback-csrf-" + providerId, "/feed");
        HttpResponse<String> callback = callback(attempt, "member-" + providerId, null);
        assertThat(callback.statusCode()).isEqualTo(302);
        String authenticatedSession = sessionCookie(callback);

        HttpResponse<String> staleToken = postLogout(authenticatedSession, ORIGIN, beforeLogin.token(), true);
        assertThat(staleToken.statusCode()).isEqualTo(403);
        assertThat(json.readTree(staleToken.body()).at("/error/code").asText()).isEqualTo("CSRF_INVALID");

        CsrfSession afterLogin = csrfSession(authenticatedSession);
        HttpResponse<String> logout = postLogout(afterLogin.cookie(), ORIGIN, afterLogin.token(), true);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().firstValue("Cache-Control")).contains("private, no-store");
        assertThat(get("/me", authenticatedSession).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("기존 Pick 생성·수정은 ADMIN만 통과한다")
    void topicWritesRequireAdminRole() throws Exception {
        long providerId = IDS.incrementAndGet();
        String binding = prepareBinding();
        BrowserAttempt attempt = beginAttempt(binding, null, "callback-admin-" + providerId, "/feed");
        HttpResponse<String> callback = callback(attempt, "member-" + providerId, null);
        assertThat(callback.statusCode()).isEqualTo(302);
        String session = sessionCookie(callback);
        CsrfSession csrf = csrfSession(session);
        long topicId = insertTopic("admin boundary");

        String createBody = json.writeValueAsString(Map.of(
                "title", "admin created", "targetDate", LocalDate.now(ZoneOffset.UTC).plusDays(1).toString(),
                "status", "OPEN", "optionAText", "A", "optionBText", "B"));
        HttpResponse<String> deniedCreate = postJson("/topics", csrf, createBody);
        HttpResponse<String> deniedUpdate = patchJson("/topics/" + topicId, csrf,
                json.writeValueAsString(Map.of("title", "user cannot update")));
        assertThat(deniedCreate.statusCode()).isEqualTo(403);
        assertThat(deniedUpdate.statusCode()).isEqualTo(403);
        assertThat(json.readTree(deniedCreate.body()).at("/error/code").asText()).isEqualTo("ADMIN_REQUIRED");
        assertThat(json.readTree(deniedUpdate.body()).at("/error/code").asText()).isEqualTo("ADMIN_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics WHERE title = 'admin created'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT title FROM topics WHERE topic_id = ?", String.class, topicId))
                .isEqualTo("admin boundary");

        long userId = jdbc.queryForObject("SELECT user_id FROM social_accounts WHERE provider_user_id = ?",
                Long.class, Long.toString(providerId));
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", userId);
        HttpResponse<String> acceptedCreate = postJson("/topics", csrf, createBody);
        HttpResponse<String> acceptedUpdate = patchJson("/topics/" + topicId, csrf,
                json.writeValueAsString(Map.of("title", "admin updated")));
        assertThat(acceptedCreate.statusCode()).isEqualTo(201);
        assertThat(acceptedUpdate.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT title FROM topics WHERE topic_id = ?", String.class, topicId))
                .isEqualTo("admin updated");
    }

    @Test
    @DisplayName("탈퇴 시 provider·세션 연결을 지우고 익명 연결을 해제한다")
    void accountWithdrawalRemovesIdentityLinksAndSession() throws Exception {
        long providerId = IDS.incrementAndGet();
        String binding = prepareBinding();
        BrowserAttempt attempt = beginAttempt(binding, null, "callback-withdraw-" + providerId, "/feed");
        HttpResponse<String> callback = callback(attempt, "member-" + providerId, null);
        String session = sessionCookie(callback);
        long userId = jdbc.queryForObject("SELECT user_id FROM social_accounts WHERE provider_user_id = ?",
                Long.class, Long.toString(providerId));
        String anonymousToken = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        long anonymousId = insertAnonymousVoter(AnonymousVoterCookie.hashIfValid(anonymousToken));
        jdbc.update("UPDATE anonymous_voters SET linked_user_id = ? WHERE anonymous_voter_id = ?", userId, anonymousId);

        CsrfSession csrf = csrfSession(session);
        HttpResponse<String> deletion = deleteCurrentMember(csrf);
        assertThat(deletion.statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE user_id = ?", String.class, userId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_accounts WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT linked_user_id FROM anonymous_voters WHERE anonymous_voter_id = ?",
                Long.class, anonymousId)).isNull();
        assertThat(sessions.findById(sessionId(session))).isNull();
        assertThat(get("/me", session).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("같은 이전 세션의 동시 callback 두 건 모두 새 세션을 유지한다")
    void concurrentCallbacksWithSharedPreexistingSessionRemainUsable() throws Exception {
        for (int run = 1; run <= 3; run++) {
            CsrfSession originalSession = csrfSession();
            String binding = prepareBinding();
            long providerId = IDS.incrementAndGet();
            String firstCode = "race-" + providerId + "-a";
            String secondCode = "race-" + providerId + "-b";
            identityClient.blockRace(String.valueOf(providerId));
            String originalCookies = combine(originalSession.cookie(), binding);
            BrowserAttempt first = beginAttempt(binding, originalSession.cookie(),
                    "callback-race-first-" + providerId, "/feed");
            BrowserAttempt second = beginAttempt(binding, originalSession.cookie(),
                    "callback-race-second-" + providerId, "/feed");

            var firstResponse = client.sendAsync(callbackRequest(first, firstCode, originalCookies), HttpResponse.BodyHandlers.ofString());
            var secondResponse = client.sendAsync(callbackRequest(second, secondCode, originalCookies), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> firstCallback = firstResponse.get(25, TimeUnit.SECONDS);
            HttpResponse<String> secondCallback = secondResponse.get(25, TimeUnit.SECONDS);
            assertThat(firstCallback.statusCode()).isEqualTo(302);
            assertThat(secondCallback.statusCode()).isEqualTo(302);
            assertThat(firstCallback.headers().firstValue("Location").orElseThrow()).contains("result=success");
            assertThat(secondCallback.headers().firstValue("Location").orElseThrow()).contains("result=success");
            assertThat(identityClient.raceArrivals(String.valueOf(providerId))).isEqualTo(2);

            assertThat(get("/me", originalCookies).statusCode()).isEqualTo(401);
            String firstSession = sessionCookie(firstCallback);
            String secondSession = sessionCookie(secondCallback);
            assertThat(firstSession).isNotEqualTo(secondSession);
            HttpResponse<String> firstMe = get("/me", firstSession);
            HttpResponse<String> secondMe = get("/me", secondSession);
            assertThat(firstMe.statusCode()).isEqualTo(200);
            assertThat(secondMe.statusCode()).isEqualTo(200);
            long firstUser = json.readTree(firstMe.body()).at("/data/id").asLong();
            long secondUser = json.readTree(secondMe.body()).at("/data/id").asLong();
            assertThat(secondUser).isEqualTo(firstUser);

            // 동시에 도착한 응답 중 어느 Set-Cookie가 브라우저 jar에 마지막으로 반영되어도 살아 있어야 합니다.
            String finalBrowserCookie = sessionCookie(run % 2 == 0 ? firstCallback : secondCallback);
            assertThat(get("/me", finalBrowserCookie).statusCode()).isEqualTo(200);
            Map<String, ? extends Session> indexed = sessions.findByPrincipalName(Long.toString(firstUser));
            assertThat(indexed).containsKeys(sessionId(firstSession), sessionId(secondSession));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id = ?", Integer.class, firstUser))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("검증되지 않은 callback은 요청값 없이 고정 실패 경로로 보낸다")
    void callbackWithoutVerifiedAttemptUsesFixedFailureRedirect() throws Exception {
        HttpResponse<String> response = get("/auth/kakao/callback?state=bad&code=never-reflect", null);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains("/auth/complete?result=failed");
        assertThat(response.headers().firstValue("Location").orElseThrow()).doesNotContain("bad", "never-reflect");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
        assertThat(identityClient.calls()).isZero();
    }

    private String prepareBinding() throws Exception {
        HttpResponse<String> response = get("/auth/kakao/prepare", null);
        assertThat(response.statusCode()).isEqualTo(204);
        String cookie = cookiePair(response, BINDING_COOKIE);
        assertThat(cookie).isNotBlank();
        return cookie;
    }

    private BrowserAttempt beginAttempt(String binding, String sessionCookie, String attemptId, String returnTo)
            throws Exception {
        String cookies = combine(sessionCookie, binding);
        String encodedReturnTo = URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
        HttpResponse<String> start = get("/auth/kakao/start?attempt=" + attemptId
                + "&returnTo=" + encodedReturnTo, cookies);
        assertThat(start.statusCode()).isEqualTo(302);
        String authorization = start.headers().firstValue("Location").orElseThrow();
        Matcher matcher = QUERY_VALUE.matcher(URI.create(authorization).getRawQuery());
        assertThat(matcher.find()).isTrue();
        String state = java.net.URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
        return new BrowserAttempt(sessionCookie, binding, attemptId, state);
    }

    private HttpResponse<String> callback(BrowserAttempt attempt, String code, String extraCookie) throws Exception {
        return client.send(callbackRequest(attempt, code, combine(attempt.sessionCookie(), attempt.bindingCookie(), extraCookie)),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest callbackRequest(BrowserAttempt attempt, String code, String cookies) {
        String path = "/auth/kakao/callback?state=" + attempt.state() + "&code=" + code;
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).header("Accept", "text/html")
                .header("Cookie", cookies).GET().build();
    }

    private CsrfSession csrfSession() throws Exception { return csrfSession(null); }

    private CsrfSession csrfSession(String existingCookie) throws Exception {
        HttpResponse<String> response = get("/csrf", existingCookie);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode envelope = json.readTree(response.body());
        assertThat(envelope.has("message")).isFalse();
        assertThat(envelope.has("error")).isFalse();
        assertThat(response.headers().firstValue("Cache-Control")).contains("private, no-store");
        String cookie = cookiePair(response, SESSION_COOKIE);
        if (cookie.isBlank()) cookie = existingCookie;
        String token = envelope.at("/data/token").asText();
        assertThat(cookie).isNotBlank();
        assertThat(token).isNotBlank();
        return new CsrfSession(cookie, token);
    }

    private HttpResponse<String> postLogout(String cookie, String origin, String token, boolean includeOrigin)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/auth/logout"))
                .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.noBody())
                .header("Cookie", cookie);
        if (includeOrigin) request.header("Origin", origin);
        if (token != null) request.header("X-CSRF-TOKEN", token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, CsrfSession csrf, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
                .header("Cookie", csrf.cookie()).header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrf.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJson(String path, CsrfSession csrf, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
                .header("Cookie", csrf.cookie()).header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrf.token())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteCurrentMember(CsrfSession csrf) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/users/me")).timeout(Duration.ofSeconds(15))
                .header("Cookie", csrf.cookie()).header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrf.token())
                .DELETE().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json,text/html,*/*").GET();
        if (cookie != null && !cookie.isBlank()) request.header("Cookie", cookie);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private static String cookiePair(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .map(value -> value.substring(0, value.indexOf(';') < 0 ? value.length() : value.indexOf(';')))
                .filter(value -> value.startsWith(name + "=") && !value.equals(name + "="))
                .findFirst().orElse("");
    }

    private static String sessionCookie(HttpResponse<?> response) {
        List<String> candidates = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(SESSION_COOKIE + "=") && !value.contains("Max-Age=0"))
                .map(value -> value.substring(0, value.indexOf(';') < 0 ? value.length() : value.indexOf(';')))
                .filter(value -> !value.equals(SESSION_COOKIE + "="))
                .toList();
        return candidates.isEmpty() ? "" : candidates.getLast();
    }

    private static String cookieValue(String cookiePair) { return cookiePair.substring(cookiePair.indexOf('=') + 1); }

    private static String sessionId(String cookiePair) {
        return new String(Base64.getUrlDecoder().decode(cookieValue(cookiePair)), StandardCharsets.UTF_8);
    }

    private static String combine(String... cookies) {
        return java.util.Arrays.stream(cookies).filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "; " + right).orElse("");
    }

    private long insertUser(String nickname) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO users (status, nickname, role) VALUES (0, ?, 'USER')", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, nickname);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertTopic(String title) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO topics (target_date, title, status) VALUES (?, ?, 'OPEN')", Statement.RETURN_GENERATED_KEYS);
            statement.setObject(1, LocalDate.now(ZoneOffset.UTC));
            statement.setString(2, title);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertOption(long topicId, String label, long count) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO topic_options (topic_id, vote_count, text, label) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, topicId);
            statement.setLong(2, count);
            statement.setString(3, label);
            statement.setString(4, label);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertAnonymousVoter(String tokenHash) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO anonymous_voters (token_hash, created_at, expires_at)
                    VALUES (?, UTC_TIMESTAMP(6), DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, tokenHash);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private static String anonymousToken(int marker) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) marker;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record BrowserAttempt(String sessionCookie, String bindingCookie, String attemptId, String state) { }
    private record CsrfSession(String cookie, String token) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIdentityConfiguration {
        @Bean
        @Primary
        FakeIdentityClient fakeIdentityClient() { return new FakeIdentityClient(); }
    }

    static class FakeIdentityClient implements KakaoIdentityClient {
        private static final Pattern MEMBER_CODE = Pattern.compile("member-(\\d+)");
        private static final Pattern RACE_CODE = Pattern.compile("race-(\\d+)-[ab]");
        private final AtomicLong calls = new AtomicLong();
        private final java.util.concurrent.ConcurrentLinkedQueue<Boolean> transactionFlags = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final Map<String, CyclicBarrier> barriers = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> raceArrivals = new ConcurrentHashMap<>();

        @Override
        public KakaoIdentity exchangeCodeAndLoadIdentity(String authorizationCode, String consumedState) {
            calls.incrementAndGet();
            transactionFlags.add(TransactionSynchronizationManager.isActualTransactionActive());
            Matcher member = MEMBER_CODE.matcher(authorizationCode);
            if (member.matches()) return new KakaoIdentity(Long.parseLong(member.group(1)));
            Matcher race = RACE_CODE.matcher(authorizationCode);
            if (!race.matches()) throw new IllegalArgumentException("test identity code rejected");
            String raceId = race.group(1);
            raceArrivals.computeIfAbsent(raceId, ignored -> new AtomicLong()).incrementAndGet();
            try {
                barriers.computeIfAbsent(raceId, ignored -> new CyclicBarrier(2)).await(15, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException("test callback barrier failed");
            }
            return new KakaoIdentity(Long.parseLong(raceId));
        }

        void blockRace(String id) {
            barriers.put(id, new CyclicBarrier(2));
            raceArrivals.put(id, new AtomicLong());
        }
        long calls() { return calls.get(); }
        long raceArrivals(String id) { return raceArrivals.get(id).get(); }
        boolean allCallsWereOutsideTransactions() { return !transactionFlags.isEmpty() && transactionFlags.stream().noneMatch(Boolean::booleanValue); }
        void reset() {
            calls.set(0);
            transactionFlags.clear();
            barriers.clear();
            raceArrivals.clear();
        }
    }
}
