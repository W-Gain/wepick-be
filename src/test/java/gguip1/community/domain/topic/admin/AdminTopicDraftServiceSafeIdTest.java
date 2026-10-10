package gguip1.community.domain.topic.admin;

import gguip1.community.domain.topic.entity.Topic;
import gguip1.community.domain.topic.entity.TopicStatus;
import gguip1.community.domain.topic.repository.TopicRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminTopicDraftServiceSafeIdTest {
    private static final long MAX_SAFE_ID = 9_007_199_254_740_991L;

    @Test
    @DisplayName("detail 응답은 2^53−1까지 허용하고 그보다 큰 ID는 DTO로 만들지 않는다")
    void boundsDetailResponseIdToJsonSafeInteger() {
        TopicRepository topics = mock(TopicRepository.class);
        Topic safe = draftTopic(MAX_SAFE_ID);
        Topic unsafe = draftTopic(MAX_SAFE_ID + 1);
        AtomicInteger calls = new AtomicInteger();
        when(topics.findTargetByIdWithOptions(1L)).thenAnswer(invocation ->
                Optional.of(calls.getAndIncrement() == 0 ? safe : unsafe));
        AdminTopicDraftService service = new AdminTopicDraftService(topics, mock(JdbcTemplate.class), Clock.systemUTC());

        assertThat(service.detail(1L).id()).isEqualTo(MAX_SAFE_ID);
        assertThatThrownBy(() -> service.detail(1L)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Topic API ID is outside JSON safe integer range");
    }

    private static Topic draftTopic(long id) {
        Topic topic = new Topic("안전 범위 확인", null, null, TopicStatus.DRAFT);
        topic.initializeDraft("안전 범위 확인", "TASTE_DAILY", null);
        ReflectionTestUtils.setField(topic, "topicId", id);
        return topic;
    }
}
