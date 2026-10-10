package gguip1.community.domain.topic.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import gguip1.community.domain.topic.admin.AdminTopicDraftCommand.Option;
import gguip1.community.domain.topic.admin.AdminTopicDraftCommand.Create;
import gguip1.community.domain.topic.entity.OptionLabel;
import gguip1.community.domain.user.entity.UserRole;
import gguip1.community.global.security.AuthenticatedMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spring Session 저장소를 거쳐 실제 보안 필터·Origin·CSRF·API 봉투를 확인합니다. */
@Testcontainers
@SpringBootTest(properties = {"app.auth.kakao.enabled=false",
        "app.security.allowed-origin=http://127.0.0.1:5173",
        "cors.allowed-origins=http://127.0.0.1:5173,https://attacker.invalid"})
@AutoConfigureMockMvc
class AdminTopicDraftHttpIntegrationTests {
    private static final String ALLOWED_ORIGIN = "http://127.0.0.1:5173";

    @Container @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminTopicDraftService draftService;
    @SuppressWarnings("rawtypes")
    @Autowired FindByIndexNameSessionRepository sessions;

    @BeforeEach
    void resetRows() {
        jdbc.update("DELETE FROM topic_status_events");
        jdbc.update("DELETE FROM votes");
        jdbc.update("DELETE FROM topic_review_checks");
        jdbc.update("DELETE FROM topic_reviews");
        jdbc.update("DELETE FROM topic_options");
        jdbc.update("DELETE FROM topics");
        jdbc.update("INSERT INTO users (user_id,status,nickname,role) VALUES (1,0,'http-admin','ADMIN') "
                + "ON DUPLICATE KEY UPDATE status=0,nickname='http-admin',role='ADMIN'");
        jdbc.update("INSERT INTO users (user_id,status,nickname,role) VALUES (2,0,'http-user','USER') "
                + "ON DUPLICATE KEY UPDATE status=0,nickname='http-user',role='USER'");
        jdbc.update("INSERT INTO users (user_id,status,nickname,role) VALUES (3,0,'http-withdrawn','USER') "
                + "ON DUPLICATE KEY UPDATE status=0,nickname='http-withdrawn',role='USER',deleted_at=NULL,withdrawn_at=NULL");
    }

    @Test
    @DisplayName("비로그인 변경은 valid Origin·CSRF 뒤 401, CSRF 누락은 403이며 USER는 ADMIN_REQUIRED다")
    void checksOriginCsrfAndRoleBeforeWrite() throws Exception {
        String adminSession = session(1L, UserRole.ADMIN);
        mvc.perform(post("/admin/topics").cookie(sessionCookie(adminSession)).contentType("application/json")
                        .content(validCreate()).header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(post("/admin/topics").cookie(sessionCookie(adminSession)).contentType("application/json")
                        .content(validCreate()).header("Origin", "https://attacker.invalid"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));

        String anonymousSession = session(null, null);
        String anonymousToken = csrfToken(anonymousSession);
        mvc.perform(post("/admin/topics").cookie(sessionCookie(anonymousSession)).contentType("application/json")
                        .content(validCreate()).header("Origin", ALLOWED_ORIGIN).header("X-CSRF-TOKEN", anonymousToken))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(patch("/admin/topics/{id}", 999L).cookie(sessionCookie(anonymousSession))
                        .contentType("application/json").content("{}").header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(patch("/admin/topics/{id}", 999L).cookie(sessionCookie(anonymousSession))
                        .contentType("application/json").content("{}").header("Origin", ALLOWED_ORIGIN)
                        .header("X-CSRF-TOKEN", anonymousToken))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        String userSession = session(2L, UserRole.USER);
        String userToken = csrfToken(userSession);
        mvc.perform(post("/admin/topics").cookie(sessionCookie(userSession)).contentType("application/json")
                        .content(validCreate()).header("Origin", ALLOWED_ORIGIN).header("X-CSRF-TOKEN", userToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ADMIN_REQUIRED"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics", Integer.class)).isZero();
    }

    @Test
    @DisplayName("비로그인은 401, USER는 ADMIN_REQUIRED, ADMIN 조회는 private no-store다")
    void separatesAnonymousUserAndAdminReads() throws Exception {
        mvc.perform(get("/admin/topics"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/admin/topics").cookie(sessionCookie(session(2L, UserRole.USER))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ADMIN_REQUIRED"));
        mvc.perform(get("/admin/topics").cookie(sessionCookie(session(1L, UserRole.ADMIN))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("단건 GET·PATCH 경로 ID가 JSON 안전 범위 밖이면 404이고 저장 상태는 그대로다")
    void rejectsUnsafePathIdsAsNotFoundWithoutChangingStorage() throws Exception {
        var created = createDraft("경로 ID 범위 기준 초안");
        jdbc.update("UPDATE topics SET status='SCHEDULED',scheduled_kst_date='2026-10-20' WHERE topic_id=?", created.id());
        String adminSession = session(1L, UserRole.ADMIN);
        String csrf = csrfToken(adminSession);
        long[] ids = {0L, -1L, 9_007_199_254_740_991L, 9_007_199_254_740_992L, Long.MAX_VALUE};
        var topicBefore = jdbc.queryForMap("SELECT title,normalized_title,category_code,content_revision,status,"
                + "scheduled_kst_date FROM topics WHERE topic_id=?", created.id());
        var optionsBefore = jdbc.queryForList("SELECT label,text FROM topic_options WHERE topic_id=? ORDER BY label", created.id());
        var eventsBefore = jdbc.queryForList("SELECT from_status,to_status,actor_user_id,occurred_at "
                + "FROM topic_status_events WHERE topic_id=? ORDER BY status_event_id", created.id());
        int topicCount = jdbc.queryForObject("SELECT COUNT(*) FROM topics", Integer.class);
        int optionCount = jdbc.queryForObject("SELECT COUNT(*) FROM topic_options", Integer.class);
        int eventCount = jdbc.queryForObject("SELECT COUNT(*) FROM topic_status_events", Integer.class);

        for (long id : ids) {
            mvc.perform(get("/admin/topics/{id}", id).cookie(sessionCookie(adminSession)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TOPIC_NOT_FOUND"));
            mvc.perform(patch("/admin/topics/{id}", id).cookie(sessionCookie(adminSession))
                            .contentType("application/json").content("{\"expectedRevision\":1,\"title\":\"변경 시도\"}")
                            .header("Origin", ALLOWED_ORIGIN).header("X-CSRF-TOKEN", csrf))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TOPIC_NOT_FOUND"));
        }

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForMap("SELECT title,normalized_title,category_code,content_revision,status,"
                + "scheduled_kst_date FROM topics WHERE topic_id=?", created.id())).isEqualTo(topicBefore);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT label,text FROM topic_options WHERE topic_id=? ORDER BY label", created.id())).containsExactlyElementsOf(optionsBefore);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList("SELECT from_status,to_status,actor_user_id,occurred_at "
                + "FROM topic_status_events WHERE topic_id=? ORDER BY status_event_id", created.id())).containsExactlyElementsOf(eventsBefore);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics", Integer.class)).isEqualTo(topicCount);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_options", Integer.class)).isEqualTo(optionCount);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_status_events", Integer.class)).isEqualTo(eventCount);
    }

    @Test
    @DisplayName("삭제 회원과 만료 세션은 관리자 목록 GET에서 인증되지 않는다")
    void rejectsDeletedMemberAndExpiredSessionOnAdminRead() throws Exception {
        String deletedMemberSession = session(3L, UserRole.USER);
        jdbc.update("UPDATE users SET status=1,deleted_at=NOW(6),withdrawn_at=NOW(6) WHERE user_id=3");
        mvc.perform(get("/admin/topics").cookie(sessionCookie(deletedMemberSession)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        String expiredSession = session(1L, UserRole.ADMIN);
        org.assertj.core.api.Assertions.assertThat(jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=0, "
                + "MAX_INACTIVE_INTERVAL=1, EXPIRY_TIME=0 WHERE SESSION_ID=?", expiredSession)).isEqualTo(1);
        mvc.perform(get("/admin/topics").cookie(sessionCookie(expiredSession)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("관리자 목록 HTTP는 status로 필터링하고 cursor를 같은 status에 묶는다")
    void filtersHttpListAndBindsCursorToStatus() throws Exception {
        var newestDraft = createDraft("목록 최신 초안");
        var olderDraft = createDraft("목록 이전 초안");
        var approved = createDraft("목록 승인 항목");
        jdbc.update("UPDATE topics SET created_at='2026-10-10 00:00:00.000003' WHERE topic_id=?", newestDraft.id());
        jdbc.update("UPDATE topics SET created_at='2026-10-10 00:00:00.000002' WHERE topic_id=?", olderDraft.id());
        jdbc.update("UPDATE topics SET status='APPROVED',created_at='2026-10-10 00:00:00.000004' WHERE topic_id=?", approved.id());
        String adminSession = session(1L, UserRole.ADMIN);

        String firstPageBody = mvc.perform(get("/admin/topics").cookie(sessionCookie(adminSession))
                        .param("status", "DRAFT").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value((int) newestDraft.id()))
                .andReturn().getResponse().getContentAsString();
        String draftCursor = json.readTree(firstPageBody).at("/data/nextCursor").asText();

        mvc.perform(get("/admin/topics").cookie(sessionCookie(adminSession))
                        .param("status", "DRAFT").param("limit", "1").param("cursor", draftCursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value((int) olderDraft.id()))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
        mvc.perform(get("/admin/topics").cookie(sessionCookie(adminSession)).param("status", "APPROVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value((int) approved.id()));
        mvc.perform(get("/admin/topics").cookie(sessionCookie(adminSession))
                        .param("status", "APPROVED").param("cursor", draftCursor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
    }

    @Test
    @DisplayName("ADMIN POST·PATCH는 공개 Location과 data 봉투를 쓰고 null·unknown 필드를 거부한다")
    void createsAndPatchesWithStrictRequestAndResponseEnvelope() throws Exception {
        String session = session(1L, UserRole.ADMIN);
        String csrf = csrfToken(session);
        var createResponse = mvc.perform(post("/admin/topics").cookie(sessionCookie(session)).contentType("application/json")
                        .content(validCreate()).header("Origin", ALLOWED_ORIGIN).header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("^/api/admin/topics/[0-9]+$")))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.contentRevision").value(1)).andReturn().getResponse();
        String created = createResponse.getContentAsString();
        long topicId = json.readTree(created).at("/data/id").asLong();
        org.assertj.core.api.Assertions.assertThat(topicId).isBetween(1L, 9_007_199_254_740_991L);
        org.assertj.core.api.Assertions.assertThat(createResponse.getHeader("Location"))
                .isEqualTo("/api/admin/topics/" + topicId);

        mvc.perform(post("/admin/topics").cookie(sessionCookie(session)).contentType("application/json")
                        .content(validCreate()).header("Origin", ALLOWED_ORIGIN).header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DUPLICATE_TOPIC"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics", Integer.class)).isEqualTo(1);

        mvc.perform(patch("/admin/topics/{id}", topicId).cookie(sessionCookie(session)).contentType("application/json")
                        .content("{\"expectedRevision\":1,\"title\":null}")
                        .header("Origin", ALLOWED_ORIGIN).header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("title"))
                .andExpect(jsonPath("$.error.details.fields[0].code").value("INVALID_FORMAT"));
        mvc.perform(patch("/admin/topics/{id}", topicId).cookie(sessionCookie(session)).contentType("application/json")
                        .content("{\"expectedRevision\":1,\"status\":\"PUBLISHED\"}")
                        .header("Origin", ALLOWED_ORIGIN).header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/admin/topics/{id}", topicId).cookie(sessionCookie(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("주말 질문은?"))
                .andExpect(jsonPath("$.data.options[0].label").value("A"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT content_revision FROM topics WHERE topic_id=?",
                Integer.class, topicId)).isEqualTo(1);
    }

    @Test
    @DisplayName("HTTP 고립 surrogate 제목과 선택지는 INVALID_FORMAT 봉투로 거부되고 저장되지 않는다")
    void rejectsIsolatedSurrogateTitleAndOptionOverHttp() throws Exception {
        String session = session(1L, UserRole.ADMIN);
        String csrf = csrfToken(session);
        String isolatedHighSurrogateEscape = "\\" + "uD800";
        String malformedTitle = "{\"title\":\"" + isolatedHighSurrogateEscape + "\","
                + "\"categoryCode\":\"TASTE_DAILY\",\"options\":["
                + "{\"label\":\"A\",\"text\":\"선택 A\"},{\"label\":\"B\",\"text\":\"선택 B\"}]}";
        String malformedOption = "{\"title\":\"정상 제목\",\"categoryCode\":\"TASTE_DAILY\",\"options\":["
                + "{\"label\":\"A\",\"text\":\"" + isolatedHighSurrogateEscape + "\"},"
                + "{\"label\":\"B\",\"text\":\"선택 B\"}]}";

        mvc.perform(post("/admin/topics").cookie(sessionCookie(session)).contentType("application/json")
                        .content(malformedTitle).header("Origin", ALLOWED_ORIGIN).header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("title"))
                .andExpect(jsonPath("$.error.details.fields[0].code").value("INVALID_FORMAT"));
        mvc.perform(post("/admin/topics").cookie(sessionCookie(session)).contentType("application/json")
                        .content(malformedOption).header("Origin", ALLOWED_ORIGIN).header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("options.a.text"))
                .andExpect(jsonPath("$.error.details.fields[0].code").value("INVALID_FORMAT"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics", Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_options", Integer.class)).isZero();
    }

    @Test
    @DisplayName("생성 OpenAPI에는 승인된 관리자 CRUD route가 생성된다")
    void generatedOpenApiListsDraftRoutes() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/admin/topics'].post").exists())
                .andExpect(jsonPath("$.paths['/admin/topics'].get").exists())
                .andExpect(jsonPath("$.paths['/admin/topics/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/admin/topics/{id}'].patch").exists());
    }

    @SuppressWarnings("unchecked")
    private String session(Long id, UserRole role) {
        Session session = (Session) sessions.createSession();
        if (id != null) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    new AuthenticatedMember(id, role), null,
                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role.name()))));
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        }
        sessions.save(session);
        return session.getId();
    }

    private String csrfToken(String sessionId) throws Exception {
        String body = mvc.perform(get("/csrf").cookie(sessionCookie(sessionId))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).at("/data/token").asText();
    }

    private static MockCookie sessionCookie(String sessionId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
        return new MockCookie("JSESSIONID", encoded);
    }

    private AdminTopicDraftViews.Detail createDraft(String title) {
        return draftService.create(1L, new Create(title, "TASTE_DAILY", List.of(
                new Option(OptionLabel.A, "선택 A"), new Option(OptionLabel.B, "선택 B"))));
    }

    private static String validCreate() {
        return """
                {"title":"주말 질문은?","categoryCode":"TASTE_DAILY","options":[
                  {"label":"A","text":"집에서 쉬기"},{"label":"B","text":"밖에서 놀기"}]}
                """;
    }
}
