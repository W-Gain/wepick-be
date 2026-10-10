package gguip1.community.domain.topic.admin;

import gguip1.community.domain.topic.admin.AdminTopicDraftCommand.Option;
import gguip1.community.domain.topic.admin.AdminTopicDraftCommand.Patch;
import gguip1.community.domain.topic.entity.OptionLabel;
import gguip1.community.domain.topic.service.TopicService;
import gguip1.community.domain.topic.dto.request.VoteRequest;
import gguip1.community.global.exception.ErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 초안 서비스의 제약·동시성·legacy 경계는 격리 MySQL에서 확인합니다. */
@Testcontainers
@SpringBootTest(properties = {"app.auth.kakao.enabled=false"})
class AdminTopicDraftServiceIntegrationTests {
    @Container @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired AdminTopicDraftService service;
    @Autowired TopicService legacyTopics;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetTopicRows() {
        jdbc.update("DELETE FROM topic_status_events");
        jdbc.update("DELETE FROM votes");
        jdbc.update("DELETE FROM topic_review_checks");
        jdbc.update("DELETE FROM topic_reviews");
        jdbc.update("DELETE FROM topic_options");
        jdbc.update("DELETE FROM topics");
        jdbc.update("INSERT INTO users (user_id,status,nickname,role) VALUES (1,0,'draft-admin','ADMIN') "
                + "ON DUPLICATE KEY UPDATE status=0,nickname='draft-admin',role='ADMIN'");
    }

    @Test
    @DisplayName("생성은 DRAFT·revision 1·현재 ADMIN·A/B·초기 이력을 같은 단위로 저장한다")
    void createsDraftWithOptionsAndInitialEvent() {
        var created = service.create(1L, create("평생 하나만 먹는다면?", "TASTE_DAILY", "짜장", "짬뽕"));

        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.contentRevision()).isEqualTo(1);
        assertThat(created.category()).isEqualTo(new AdminTopicDraftViews.Category("TASTE_DAILY", "취향·일상"));
        assertThat(created.createdByUserId()).isEqualTo(1L);
        assertThat(created.scheduledKstDate()).isNull();
        assertThat(created.createdAt()).endsWith("+09:00");
        assertThat(jdbc.queryForList("SELECT label FROM topic_options WHERE topic_id=? ORDER BY label", String.class,
                created.id())).containsExactly("A", "B");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_status_events WHERE topic_id=? "
                + "AND from_status IS NULL AND to_status='DRAFT' AND actor_user_id=1", Integer.class, created.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT target_date IS NULL FROM topics WHERE topic_id=?", Boolean.class,
                created.id())).isTrue();
    }

    @Test
    @DisplayName("topic·option·초기 상태 event 각 INSERT 실패는 생성 전체를 롤백한다")
    void createRollsBackWhenEachInsertStageFails() {
        assertCreateRollsBackWhenInsertFails("topics", "topic insert failure");
        assertCreateRollsBackWhenInsertFails("topic_options", "option insert failure");
        assertCreateRollsBackWhenInsertFails("topic_status_events", "event insert failure");
    }

    @Test
    @DisplayName("PATCH 상태 이력 INSERT가 실패하면 내용·revision·상태·예약·옵션까지 롤백한다")
    void patchRollsBackWhenStatusEventInsertFails() {
        var created = service.create(1L, create("상태 이력 실패 전 제목", "TASTE_DAILY", "기존 A", "기존 B"));
        jdbc.update("UPDATE topics SET status='SCHEDULED',scheduled_kst_date='2026-10-20' WHERE topic_id=?", created.id());
        var topicBefore = jdbc.queryForMap("SELECT title,normalized_title,category_code,content_revision,status,"
                + "scheduled_kst_date FROM topics WHERE topic_id=?", created.id());
        var optionsBefore = jdbc.queryForList("SELECT label,text FROM topic_options WHERE topic_id=? ORDER BY label", created.id());
        var eventsBefore = statusEvents(created.id());
        String constraint = "ck_test_fail_patch_status_event";
        jdbc.execute("ALTER TABLE topic_status_events ADD CONSTRAINT " + constraint
                + " CHECK (from_status IS NULL OR from_status <> 'SCHEDULED' OR to_status <> 'DRAFT')");
        try {
            assertThatThrownBy(() -> service.patch(created.id(), 1L,
                    patch(1, "수정된 제목", "VALUES_RELATIONSHIPS", "새 A", "새 B")))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .isNotInstanceOf(AdminTopicDraftException.class);
            assertThat(jdbc.queryForMap("SELECT title,normalized_title,category_code,content_revision,status,"
                    + "scheduled_kst_date FROM topics WHERE topic_id=?", created.id())).isEqualTo(topicBefore);
            assertThat(jdbc.queryForList("SELECT label,text FROM topic_options WHERE topic_id=? ORDER BY label", created.id()))
                    .containsExactlyElementsOf(optionsBefore);
            assertThat(statusEvents(created.id())).containsExactlyElementsOf(eventsBefore);
        } finally {
            jdbc.execute("ALTER TABLE topic_status_events DROP CHECK " + constraint);
        }
    }

    @Test
    @DisplayName("topics.actor FK 오류는 DUPLICATE_TOPIC으로 바뀌지 않고 생성 행을 남기지 않는다")
    void foreignKeyFailureIsNotClassifiedAsDuplicateTitle() {
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.create(999_999L, create("FK 실패 질문", "TASTE_DAILY", "A", "B")));
        assertThat(failure)
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .isNotInstanceOf(AdminTopicDraftException.class);
        Throwable rootCause = org.springframework.core.NestedExceptionUtils.getRootCause(failure);
        assertThat(rootCause).isInstanceOf(java.sql.SQLException.class);
        assertThat(rootCause.getMessage().toLowerCase()).contains("foreign key");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_options", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_status_events", Integer.class)).isZero();
    }

    @Test
    @DisplayName("PATCH no-op은 revision·event를 유지하고 실제 변경은 기존 option ID와 vote 참조를 보존한다")
    void noOpAndContentEditPreserveRevisionAndOptionRows() {
        var created = service.create(1L, create("원래 질문", "TASTE_DAILY", "예", "아니요"));
        List<Long> optionIds = optionIds(created.id());
        jdbc.update("INSERT INTO votes (user_id,topic_id,option_id) VALUES (1,?,?)", created.id(), optionIds.getFirst());

        var noOp = service.patch(created.id(), 1L, patch(1, "원래 질문", "TASTE_DAILY", "예", "아니요"));
        assertThat(noOp.contentRevision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_status_events WHERE topic_id=?", Integer.class,
                created.id())).isEqualTo(1);

        var updated = service.patch(created.id(), 1L, patch(1, "수정한 질문", "VALUES_RELATIONSHIPS", "그렇다", "아니다"));
        assertThat(updated.contentRevision()).isEqualTo(2);
        assertThat(updated.title()).isEqualTo("수정한 질문");
        assertThat(optionIds(created.id())).containsExactlyElementsOf(optionIds);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM votes WHERE topic_id=? AND option_id=?", Integer.class,
                created.id(), optionIds.getFirst())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_status_events WHERE topic_id=?", Integer.class,
                created.id())).isEqualTo(1);
        assertThatThrownBy(() -> service.patch(created.id(), 1L, patch(1, "수정한 질문", "VALUES_RELATIONSHIPS", "그렇다", "아니다")))
                .isInstanceOf(AdminTopicDraftException.class)
                .extracting(error -> ((AdminTopicDraftException) error).code()).isEqualTo("REVISION_CONFLICT");
    }

    @Test
    @DisplayName("APPROVED와 SCHEDULED 실제 편집은 DRAFT로 돌아가고 예약을 지우며 상태 이력을 남긴다")
    void approvedAndScheduledEditsReturnToDraft() {
        for (String state : List.of("APPROVED", "SCHEDULED")) {
            var created = service.create(1L, create("원본 " + state, "TASTE_DAILY", "A", "B"));
            if (state.equals("SCHEDULED")) {
                jdbc.update("UPDATE topics SET status=?,scheduled_kst_date='2026-10-20' WHERE topic_id=?", state, created.id());
            } else {
                jdbc.update("UPDATE topics SET status=? WHERE topic_id=?", state, created.id());
            }

            var updated = service.patch(created.id(), 1L, patch(1, "변경 " + state, "TASTE_DAILY", "A", "B"));

            assertThat(updated.status()).isEqualTo("DRAFT");
            assertThat(updated.contentRevision()).isEqualTo(2);
            assertThat(updated.scheduledKstDate()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_status_events WHERE topic_id=? "
                    + "AND from_status=? AND to_status='DRAFT' AND actor_user_id=1", Integer.class, created.id(), state)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("published_at 이력은 막고 REJECTED 실제 수정은 과거 review와 상태 이력을 보존해 재검수로 돌린다")
    void publishedHistoryIsImmutableAndRejectedEditsPreserveReviewHistory() {
        var published = service.create(1L, create("공개 질문", "TASTE_DAILY", "A", "B"));
        jdbc.update("UPDATE topics SET status='PUBLISHED',published_at='2026-10-10 01:02:03.123456' WHERE topic_id=?", published.id());
        assertThatThrownBy(() -> service.patch(published.id(), 1L, patch(1, "공개 질문", "TASTE_DAILY", "A", "B")))
                .isInstanceOf(AdminTopicDraftException.class)
                .extracting(error -> ((AdminTopicDraftException) error).code()).isEqualTo("INVALID_STATUS_TRANSITION");

        var rejected = service.create(1L, create("거절 질문", "TASTE_DAILY", "A", "B"));
        jdbc.update("UPDATE topics SET status='REJECTED',scheduled_kst_date='2026-10-20' WHERE topic_id=?", rejected.id());
        jdbc.update("INSERT INTO topic_reviews (topic_id,content_revision,reviewer_user_id,decision,memo,reviewed_at) "
                + "VALUES (?,1,1,'REJECTED','수정 후 재검수 필요','2026-10-10 01:02:03.123456')", rejected.id());
        long reviewId = jdbc.queryForObject("SELECT MAX(review_id) FROM topic_reviews WHERE topic_id=?", Long.class, rejected.id());
        jdbc.update("INSERT INTO topic_review_checks (review_id,criterion_code,passed) VALUES (?, 'TITLE_CLEAR', 0)", reviewId);
        jdbc.update("INSERT INTO topic_status_events (topic_id,actor_user_id,from_status,to_status,occurred_at) "
                + "VALUES (?,1,'DRAFT','REJECTED','2026-10-10 01:02:03.123456')", rejected.id());

        List<Map<String, Object>> oldEvents = statusEvents(rejected.id());
        List<Map<String, Object>> oldReviews = jdbc.queryForList("SELECT review_id,content_revision,reviewer_user_id,decision,memo,reviewed_at "
                + "FROM topic_reviews WHERE topic_id=? ORDER BY review_id", rejected.id());
        List<Map<String, Object>> oldChecks = jdbc.queryForList("SELECT review_check_id,review_id,criterion_code,passed "
                + "FROM topic_review_checks WHERE review_id=? ORDER BY review_check_id", reviewId);

        var noOp = service.patch(rejected.id(), 1L, patch(1, "거절 질문", "TASTE_DAILY", "A", "B"));
        assertThat(noOp.status()).isEqualTo("REJECTED");
        assertThat(noOp.contentRevision()).isEqualTo(1);
        assertThat(noOp.scheduledKstDate()).isEqualTo("2026-10-20");
        assertThatThrownBy(() -> service.patch(rejected.id(), 1L, patch(0, "거절 질문", "TASTE_DAILY", "A", "B")))
                .isInstanceOf(AdminTopicDraftException.class)
                .extracting(error -> ((AdminTopicDraftException) error).code()).isEqualTo("REVISION_CONFLICT");
        assertThat(statusEvents(rejected.id())).containsExactlyElementsOf(oldEvents);
        assertThat(jdbc.queryForList("SELECT review_id,content_revision,reviewer_user_id,decision,memo,reviewed_at "
                + "FROM topic_reviews WHERE topic_id=? ORDER BY review_id", rejected.id())).containsExactlyElementsOf(oldReviews);
        assertThat(jdbc.queryForList("SELECT review_check_id,review_id,criterion_code,passed "
                + "FROM topic_review_checks WHERE review_id=? ORDER BY review_check_id", reviewId)).containsExactlyElementsOf(oldChecks);

        var updated = service.patch(rejected.id(), 1L, patch(1, "다시 쓴 질문", "TASTE_DAILY", "A", "B"));
        assertThat(updated.status()).isEqualTo("DRAFT");
        assertThat(updated.contentRevision()).isEqualTo(2);
        assertThat(updated.scheduledKstDate()).isNull();
        List<Map<String, Object>> updatedEvents = statusEvents(rejected.id());
        assertThat(updatedEvents).hasSize(oldEvents.size() + 1);
        assertThat(updatedEvents.subList(0, oldEvents.size())).containsExactlyElementsOf(oldEvents);
        assertThat(updatedEvents.getLast()).containsEntry("from_status", "REJECTED")
                .containsEntry("to_status", "DRAFT").containsEntry("actor_user_id", 1L);
        assertThat(updatedEvents.stream().filter(event -> "REJECTED".equals(event.get("from_status"))
                && "DRAFT".equals(event.get("to_status")))).hasSize(1);
        assertThat(jdbc.queryForList("SELECT review_id,content_revision,reviewer_user_id,decision,memo,reviewed_at "
                + "FROM topic_reviews WHERE topic_id=? ORDER BY review_id", rejected.id())).containsExactlyElementsOf(oldReviews);
        assertThat(jdbc.queryForList("SELECT review_check_id,review_id,criterion_code,passed "
                + "FROM topic_review_checks WHERE review_id=? ORDER BY review_check_id", reviewId)).containsExactlyElementsOf(oldChecks);

        jdbc.update("UPDATE topics SET published_at='2026-10-10 02:03:04.123456' WHERE topic_id=?", rejected.id());
        assertThatThrownBy(() -> service.patch(rejected.id(), 1L, patch(2, "다시 쓴 질문", "TASTE_DAILY", "A", "B")))
                .isInstanceOf(AdminTopicDraftException.class)
                .extracting(error -> ((AdminTopicDraftException) error).code()).isEqualTo("INVALID_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("제목 중복은 legacy 상태·카테고리와 무관하게 UNIQUE 및 서비스 검사로 차단한다")
    void titleUniquenessIncludesLegacyRows() {
        jdbc.update("INSERT INTO topics (target_date,title,normalized_title,content_revision,status) "
                + "VALUES ('2026-10-10','café—질문','café-질문',1,'CLOSED')");

        assertThatThrownBy(() -> service.create(1L, create("  café–질문 ", "TASTE_DAILY", "A", "B")))
                .isInstanceOf(AdminTopicDraftException.class)
                .extracting(error -> ((AdminTopicDraftException) error).code()).isEqualTo("DUPLICATE_TOPIC");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_status_events", Integer.class)).isZero();
    }

    @Test
    @DisplayName("관리자 cursor는 created_at 미세초와 ID tie-break를 적용하고 target 행만 반환한다")
    void keysetListsTargetRowsWithStableMicrosecondOrder() {
        var older = service.create(1L, create("이전 제목", "TASTE_DAILY", "A", "B"));
        var tiedLowId = service.create(1L, create("같은 시각 낮은 ID", "VALUES_RELATIONSHIPS", "A", "B"));
        var tiedHighId = service.create(1L, create("같은 시각 높은 ID", "SOCIETY_TRENDS", "A", "B"));
        jdbc.update("UPDATE topics SET created_at='2026-10-10 00:00:00.123455' WHERE topic_id=?", older.id());
        jdbc.update("UPDATE topics SET created_at='2026-10-10 00:00:00.123456' WHERE topic_id IN (?,?)",
                tiedLowId.id(), tiedHighId.id());

        var firstPage = service.list("DRAFT", null, 2);
        var secondPage = service.list("DRAFT", firstPage.nextCursor(), 2);

        assertThat(firstPage.items()).extracting(AdminTopicDraftViews.ListItem::id)
                .containsExactly(tiedHighId.id(), tiedLowId.id());
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(secondPage.items()).extracting(AdminTopicDraftViews.ListItem::id).containsExactly(older.id());
        assertThat(secondPage.nextCursor()).isNull();
        jdbc.update("INSERT INTO topics (target_date,title,normalized_title,content_revision,status) "
                + "VALUES ('2026-10-10','legacy 목록 행','legacy 목록 행',1,'OPEN')");
        assertThat(service.list(null, null, 10).items()).extracting(AdminTopicDraftViews.ListItem::id)
                .containsExactly(tiedHighId.id(), tiedLowId.id(), older.id());
    }

    @Test
    @DisplayName("동일 normalized title 생성 경합은 5회 모두 한 행·한 초기 event만 남긴다")
    void normalizedTitleConcurrentCreatesAreAtomicAcrossFiveRounds() throws Exception {
        for (int round = 0; round < 5; round++) {
            String title = "동시 질문 " + round + "—하나";
            var outcomes = concurrently(() -> service.create(1L, create(title, "TASTE_DAILY", "A", "B")),
                    () -> service.create(1L, create(title.replace('—', '–'), "SOCIETY_TRENDS", "A", "B")));
            assertThat(outcomes.stream().filter(value -> value instanceof AdminTopicDraftViews.Detail)).hasSize(1);
            assertThat(outcomes.stream().filter(value -> value instanceof AdminTopicDraftException)).hasSize(1);
            assertThat(outcomes.stream().filter(AdminTopicDraftException.class::isInstance)
                    .map(value -> ((AdminTopicDraftException) value).code()).toList())
                    .containsExactly("DUPLICATE_TOPIC");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics WHERE normalized_title LIKE ?", Integer.class,
                    "동시 질문 " + round + "%")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_status_events e JOIN topics t USING(topic_id) "
                    + "WHERE t.normalized_title LIKE ?", Integer.class, "동시 질문 " + round + "%")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("같은 expectedRevision 수정 경합은 5회 모두 한 번만 반영한다")
    void sameRevisionConcurrentPatchesSerializeAcrossFiveRounds() throws Exception {
        for (int round = 0; round < 5; round++) {
            int iteration = round;
            var created = service.create(1L, create("revision 질문 " + round, "TASTE_DAILY", "A", "B"));
            var outcomes = concurrently(
                    () -> service.patch(created.id(), 1L, patch(1, "first " + iteration, "TASTE_DAILY", "A", "B")),
                    () -> service.patch(created.id(), 1L, patch(1, "second " + iteration, "TASTE_DAILY", "A", "B")));
            assertThat(outcomes.stream().filter(value -> value instanceof AdminTopicDraftViews.Detail)).hasSize(1);
            assertThat(outcomes.stream().filter(value -> value instanceof AdminTopicDraftException)).hasSize(1);
            assertThat(outcomes.stream().filter(AdminTopicDraftException.class::isInstance)
                    .map(value -> ((AdminTopicDraftException) value).code()).toList())
                    .containsExactly("REVISION_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT content_revision FROM topics WHERE topic_id=?", Integer.class,
                    created.id())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_status_events WHERE topic_id=?", Integer.class,
                    created.id())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("legacy 목록·투표는 새 DRAFT를 반환하거나 변경하지 않는다")
    void legacyArchiveAndVoteDoNotHandleDraftRows() {
        var created = service.create(1L, create("비공개 초안", "TASTE_DAILY", "A", "B"));

        var archive = legacyTopics.getTopicArchive(org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(archive.getContent()).extracting(item -> item.getTopicId()).doesNotContain(created.id());
        assertThatThrownBy(() -> legacyTopics.vote(created.id(), new VoteRequest(optionIds(created.id()).getFirst()), 1L))
                .isInstanceOf(ErrorException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM votes WHERE topic_id=?", Integer.class, created.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM topics WHERE topic_id=?", String.class, created.id())).isEqualTo("DRAFT");
    }

    private static AdminTopicDraftCommand.Create create(String title, String category, String a, String b) {
        return new AdminTopicDraftCommand.Create(title, category, List.of(
                new Option(OptionLabel.A, a), new Option(OptionLabel.B, b)));
    }

    private static Patch patch(int revision, String title, String category, String a, String b) {
        return new Patch(revision, true, title, true, category, true, List.of(
                new Option(OptionLabel.A, a), new Option(OptionLabel.B, b)));
    }

    private List<Long> optionIds(long topicId) {
        return jdbc.queryForList("SELECT option_id FROM topic_options WHERE topic_id=? ORDER BY label", Long.class, topicId);
    }

    private List<Map<String, Object>> statusEvents(long topicId) {
        return jdbc.queryForList("SELECT status_event_id,from_status,to_status,actor_user_id,occurred_at "
                + "FROM topic_status_events WHERE topic_id=? ORDER BY status_event_id", topicId);
    }

    private void assertCreateRollsBackWhenInsertFails(String table, String title) {
        String constraint = "ck_test_fail_" + table;
        String predicate = switch (table) {
            case "topics" -> "title <> '" + title + "'";
            case "topic_options" -> "label <> 'A'";
            case "topic_status_events" -> "to_status <> 'DRAFT'";
            default -> throw new IllegalArgumentException("unexpected draft insert table");
        };
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " CHECK (" + predicate + ")");
        try {
            assertThatThrownBy(() -> service.create(1L, create(title, "TASTE_DAILY", "A", "B")))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .isNotInstanceOf(AdminTopicDraftException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_options", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic_status_events", Integer.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE " + table + " DROP CHECK " + constraint);
        }
    }

    private static List<Object> concurrently(ThrowingSupplier first, ThrowingSupplier second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            var a = executor.submit(() -> runAfterBarrier(barrier, first));
            var b = executor.submit(() -> runAfterBarrier(barrier, second));
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static Object runAfterBarrier(CyclicBarrier barrier, ThrowingSupplier supplier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return supplier.get();
        } catch (AdminTopicDraftException expectedConflict) {
            return expectedConflict;
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}
