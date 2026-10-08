package gguip1.community;

import gguip1.community.domain.topic.entity.*;
import gguip1.community.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class CommunityApplicationTests {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;
    @Autowired Environment environment;

    @Test
    void emptyDatabaseMigratesOnceAndHibernateValidates() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.session.jdbc.initialize-schema")).isEqualTo("never");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class)).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                String.class)).containsExactlyInAnyOrder(
                "images", "users", "posts", "post_comments", "post_images", "post_likes", "post_stats",
                "topics", "topic_options", "votes", "SPRING_SESSION", "SPRING_SESSION_ATTRIBUTES",
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
