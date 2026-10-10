package gguip1.community;

import gguip1.community.domain.topic.entity.OptionLabel;
import gguip1.community.domain.topic.entity.Topic;
import gguip1.community.domain.topic.entity.TopicOption;
import gguip1.community.domain.topic.entity.TopicStatus;
import gguip1.community.domain.topic.entity.Vote;
import gguip1.community.domain.topic.admin.TopicTitleNormalizer;
import gguip1.community.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import gguip1.community.global.security.CurrentActorFilter;
import gguip1.community.global.security.OriginGuardFilter;
import org.springframework.security.core.userdetails.UserDetailsService;
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
    @Autowired ApplicationContext applicationContext;
    @Autowired FilterRegistrationBean<OriginGuardFilter> originGuardRegistration;

    @Test
    @DisplayName("빈 MySQL에 V1부터 V5까지 한 번 적용하고 Hibernate가 스키마를 검증한다")
    void emptyDatabaseMigratesOnceAndHibernateValidates() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
        for (String version : new String[]{"1", "2", "3", "4", "5"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success = 1",
                    Integer.class, version)).isEqualTo(1);
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                String.class)).contains(
                "users", "votes", "social_accounts", "anonymous_voters", "login_attempts",
                "SPRING_SESSION", "SPRING_SESSION_ATTRIBUTES", "flyway_schema_history");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'SPRING_SESSION'
                  AND INDEX_NAME = 'SPRING_SESSION_IX3' AND COLUMN_NAME = 'PRINCIPAL_NAME'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("기존 사용자·주제·투표 매핑을 저장하고 다시 읽는다")
    void existingUserTopicAndVoteMappingsStillPersist() {
        User user = User.builder().nickname("baseline").build();
        entityManager.persist(user);
        Topic topic = new Topic("Existing question", "Existing description", LocalDate.now(), TopicStatus.OPEN);
        topic.setNormalizedTitle(TopicTitleNormalizer.normalize(topic.getTitle()).normalizedTitle());
        TopicOption optionA = new TopicOption(topic, OptionLabel.A, "A", null);
        TopicOption optionB = new TopicOption(topic, OptionLabel.B, "B", null);
        topic.addOption(optionA);
        topic.addOption(optionB);
        entityManager.persist(topic);
        Vote vote = new Vote(topic, user, optionA);
        entityManager.persist(vote);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(User.class, user.getUserId()).getNickname()).isEqualTo("baseline");
        assertThat(entityManager.find(Topic.class, topic.getTopicId()).getOptions()).hasSize(2);
        assertThat(entityManager.find(Vote.class, vote.getVoteId()).getSelectedOption().getLabel()).isEqualTo(OptionLabel.A);
    }

    @Test
    @DisplayName("명시적 Security 체인은 생성 비밀번호 사용자 없이 CSRF 뒤에서 회원을 확인한다")
    void explicitSpringSecurityChainHasNoGeneratedPasswordUser() {
        assertThat(applicationContext.getBeansOfType(SecurityFilterChain.class)).isNotEmpty();
        assertThat(applicationContext.getBeansOfType(UserDetailsService.class)).isEmpty();
        SecurityFilterChain chain = applicationContext.getBeansOfType(SecurityFilterChain.class).values().iterator().next();
        var filters = chain.getFilters();
        int csrfIndex = java.util.stream.IntStream.range(0, filters.size())
                .filter(index -> filters.get(index) instanceof CsrfFilter).findFirst().orElseThrow();
        int actorIndex = java.util.stream.IntStream.range(0, filters.size())
                .filter(index -> filters.get(index) instanceof CurrentActorFilter).findFirst().orElseThrow();
        assertThat(actorIndex).isGreaterThan(csrfIndex);
        assertThat(originGuardRegistration.getOrder()).isEqualTo(-200);
        assertThat(originGuardRegistration.getOrder()).isLessThan(-100);
    }

    @Test
    @DisplayName("Spring Session JDBC가 principal index로 세션을 찾고 속성을 정리한다")
    void jdbcSessionsSaveLookupAndCascadeDeleteAttributes() {
        verifySessionStorage(sessions);
    }

    private <S extends Session> void verifySessionStorage(FindByIndexNameSessionRepository<S> repository) {
        S session = repository.createSession();
        session.setAttribute("userId", 42L);
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "42");
        repository.save(session);

        assertThat(repository.findById(session.getId()).<Long>getAttribute("userId")).isEqualTo(42L);
        assertThat(repository.findByPrincipalName("42")).containsKey(session.getId());
        String primaryId = jdbc.queryForObject("SELECT PRIMARY_ID FROM SPRING_SESSION WHERE SESSION_ID = ?",
                String.class, session.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE SESSION_PRIMARY_ID = ?",
                Integer.class, primaryId)).isEqualTo(2);
        repository.deleteById(session.getId());
        assertThat(repository.findById(session.getId())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE SESSION_PRIMARY_ID = ?",
                Integer.class, primaryId)).isZero();
    }
}
