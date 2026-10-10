package gguip1.community.domain.topic.service;

import gguip1.community.domain.topic.dto.request.VoteRequest;
import gguip1.community.domain.topic.entity.Topic;
import gguip1.community.domain.topic.entity.TopicOption;
import gguip1.community.domain.topic.entity.TopicStatus;
import gguip1.community.domain.topic.repository.TopicOptionRepository;
import gguip1.community.domain.topic.repository.TopicRepository;
import gguip1.community.domain.topic.repository.VoteRepository;
import gguip1.community.domain.user.entity.User;
import gguip1.community.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TopicServiceKstDateTest {

    @Test
    @DisplayName("KST 자정 직전에는 UTC 날짜가 아니라 KST 당일로 오늘 토픽을 조회한다")
    void getTodayTopic_justBeforeKstMidnight_usesKstDate() {
        assertLookupDate("2040-12-31T14:59:59Z", LocalDate.of(2040, 12, 31));
    }

    @Test
    @DisplayName("UTC 15시인 KST 자정부터 다음 KST 날짜로 오늘 토픽을 조회한다")
    void getTodayTopic_atKstMidnight_usesNextKstDate() {
        assertLookupDate("2040-12-31T15:00:00Z", LocalDate.of(2041, 1, 1));
    }

    @Test
    @DisplayName("UTC 15시 직후에도 KST 날짜 기준을 유지한다")
    void getTodayTopic_justAfterKstMidnight_usesNextKstDate() {
        assertLookupDate("2040-12-31T15:00:01Z", LocalDate.of(2041, 1, 1));
    }

    @Test
    @DisplayName("UTC 날짜가 전날이어도 KST 자정에 시작한 오늘 토픽은 투표할 수 있다")
    void vote_atKstMidnight_acceptsKstTodayTopic() {
        // 이 회귀는 날짜 기준만 확인하고, 기존 오늘 토픽 투표 제한은 그대로 둔다.
        Fixture fixture = fixture("2040-12-31T15:00:00Z");
        User user = mock(User.class);
        Topic topic = mock(Topic.class);
        TopicOption option = mock(TopicOption.class);
        Long topicId = 21L;
        Long optionId = 34L;

        given(fixture.userRepository.findById(7L)).willReturn(Optional.of(user));
        given(fixture.topicRepository.findById(topicId)).willReturn(Optional.of(topic));
        given(topic.getTargetDate()).willReturn(LocalDate.of(2041, 1, 1));
        given(fixture.voteRepository.existsByTopicAndUser(topic, user)).willReturn(false);
        given(fixture.topicOptionRepository.findById(optionId)).willReturn(Optional.of(option));
        given(option.getOptionId()).willReturn(optionId);
        given(option.getTopic()).willReturn(topic);
        given(topic.getTopicId()).willReturn(topicId);

        assertThatCode(() -> fixture.service.vote(topicId, new VoteRequest(optionId), 7L))
                .doesNotThrowAnyException();

        verify(fixture.voteRepository).save(any());
        verify(fixture.topicOptionRepository).incrementVoteCount(optionId);
    }

    private void assertLookupDate(String instant, LocalDate expectedDate) {
        Fixture fixture = fixture(instant);
        Topic topic = new Topic("KST date", null, expectedDate, TopicStatus.OPEN);
        given(fixture.topicRepository.findByTargetDateWithOptions(any(LocalDate.class)))
                .willReturn(Optional.of(topic));

        var response = fixture.service.getTodayTopic(null);

        assertThat(response).isNotNull();
        assertThat(response.getTargetDate()).isEqualTo(expectedDate);
        verify(fixture.topicRepository).findByTargetDateWithOptions(eq(expectedDate));
    }

    private Fixture fixture(String instant) {
        TopicRepository topicRepository = mock(TopicRepository.class);
        TopicOptionRepository topicOptionRepository = mock(TopicOptionRepository.class);
        VoteRepository voteRepository = mock(VoteRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);

        return new Fixture(
                new TopicService(topicRepository, topicOptionRepository, voteRepository, userRepository, clock),
                topicRepository,
                topicOptionRepository,
                voteRepository,
                userRepository);
    }

    private record Fixture(
            TopicService service,
            TopicRepository topicRepository,
            TopicOptionRepository topicOptionRepository,
            VoteRepository voteRepository,
            UserRepository userRepository) {
    }
}
