package gguip1.community;

import com.fasterxml.jackson.databind.JsonNode;
import gguip1.community.domain.topic.entity.*;
import gguip1.community.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommunityApplicationTests {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;
    @Autowired Environment environment;
    @Autowired TestRestTemplate http;

    @Test
    void emptyDatabaseMigratesOnceAndHibernateValidates() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.session.jdbc.initialize-schema")).isEqualTo("never");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = 1",
                Integer.class)).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                String.class)).containsExactlyInAnyOrder(
                "images", "users", "posts", "post_comments", "post_images", "post_likes", "post_stats",
                "topics", "topic_options", "votes", "SPRING_SESSION", "SPRING_SESSION_ATTRIBUTES",
                "social_accounts", "anonymous_voters", "login_attempts", "external_unlink_jobs",
                "flyway_schema_history");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'SPRING_SESSION'
                  AND INDEX_NAME = 'SPRING_SESSION_IX3' AND COLUMN_NAME = 'PRINCIPAL_NAME'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    @Transactional
    void existingUserTopicAndVoteMappingsStillPersist() {
        User user = User.builder().email("baseline@example.com").password("existing-password")
                .nickname("baseline").build();
        entityManager.persist(user);
        Topic topic = new Topic("Existing question", "Existing description", LocalDate.now(), TopicStatus.OPEN);
        TopicOption optionA = new TopicOption(topic, OptionLabel.A, "A", null);
        TopicOption optionB = new TopicOption(topic, OptionLabel.B, "B", null);
        topic.addOption(optionA);
        topic.addOption(optionB);
        entityManager.persist(topic);
        Vote vote = new Vote(topic, user, optionA);
        entityManager.persist(vote);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(User.class, user.getUserId()).getEmail()).isEqualTo("baseline@example.com");
        assertThat(entityManager.find(Topic.class, topic.getTopicId()).getOptions()).hasSize(2);
        assertThat(entityManager.find(Vote.class, vote.getVoteId()).getSelectedOption().getLabel()).isEqualTo(OptionLabel.A);
    }

    @Test
    void jdbcSessionsSaveLookupAndCascadeDeleteAttributes() {
        verifySessionStorage(sessions);
    }

    @Test
    void legacyRegistrationLoginAndVotingStillWorkOverHttp() {
        Map<String, Object> registration = Map.of(
                "email", "http-v2@example.com", "password", "Migration1!",
                "password2", "Migration1!", "nickname", "http-v2");
        assertThat(http.postForEntity("/users", registration, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var login = http.postForEntity("/auth", Map.of("email", "http-v2@example.com", "password", "Migration1!"), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().path("data").path("email").asText()).isEqualTo("http-v2@example.com");
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";", 2)[0]);
        var me = http.exchange("/users/me", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().path("data").path("nickname").asText()).isEqualTo("http-v2");
        var topic = http.exchange("/topics", HttpMethod.POST, new HttpEntity<>(Map.of(
                "title", "V2 HTTP regression", "targetDate", LocalDate.now().toString(), "status", "OPEN",
                "optionAText", "A", "optionBText", "B"), headers), JsonNode.class);
        assertThat(topic.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long topicId = topic.getBody().path("data").asLong();
        Long optionId = jdbc.queryForObject("SELECT option_id FROM topic_options WHERE topic_id = ? AND label = 'A'", Long.class, topicId);
        var vote = new HttpEntity<>(Map.of("optionId", optionId), headers);
        String votePath = "/topics/" + topicId + "/vote";
        assertThat(http.exchange(votePath, HttpMethod.POST, vote, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var duplicate = http.exchange(votePath, HttpMethod.POST, vote, JsonNode.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().path("message").asText()).isEqualTo("DUPLICATE_VOTE");
        assertThat(jdbc.queryForObject("SELECT vote_count FROM topic_options WHERE option_id = ?", Long.class, optionId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM votes WHERE topic_id = ?", Integer.class, topicId)).isEqualTo(1);
        assertThat(http.exchange("/auth", HttpMethod.DELETE, new HttpEntity<>(headers), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.exchange("/users/me", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private <S extends Session> void verifySessionStorage(FindByIndexNameSessionRepository<S> sessions) {
        S session = sessions.createSession();
        session.setAttribute("userId", 42L);
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "42");
        sessions.save(session);

        assertThat(sessions.findById(session.getId()).<Long>getAttribute("userId")).isEqualTo(42L);
        assertThat(sessions.findByPrincipalName("42")).containsKey(session.getId());
        String primaryId = jdbc.queryForObject("SELECT PRIMARY_ID FROM SPRING_SESSION WHERE SESSION_ID = ?",
                String.class, session.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE SESSION_PRIMARY_ID = ?",
                Integer.class, primaryId)).isEqualTo(2);
        sessions.deleteById(session.getId());
        assertThat(sessions.findById(session.getId())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE SESSION_PRIMARY_ID = ?",
                Integer.class, primaryId)).isZero();
    }
}
