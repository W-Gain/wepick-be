package gguip1.community.domain.topic.service;

import gguip1.community.domain.topic.dto.request.CreateTopicRequest;
import gguip1.community.domain.topic.dto.request.UpdateTopicRequest;
import gguip1.community.domain.topic.dto.request.VoteRequest;
import gguip1.community.domain.topic.dto.response.TopicListResponse;
import gguip1.community.domain.topic.dto.response.TopicOptionResponse;
import gguip1.community.domain.topic.dto.response.TopicResponse;
import gguip1.community.domain.topic.admin.TopicDuplicateKey;
import gguip1.community.domain.topic.admin.TopicTitleNormalizer;
import gguip1.community.domain.topic.entity.OptionLabel;
import gguip1.community.domain.topic.entity.Topic;
import gguip1.community.domain.topic.entity.TopicOption;
import gguip1.community.domain.topic.entity.TopicStatus;
import gguip1.community.domain.topic.entity.Vote;
import gguip1.community.domain.topic.repository.TopicOptionRepository;
import gguip1.community.domain.topic.repository.TopicRepository;
import gguip1.community.domain.topic.repository.VoteRepository;
import gguip1.community.domain.user.entity.User;
import gguip1.community.domain.user.repository.UserRepository;
import gguip1.community.global.exception.ErrorCode;
import gguip1.community.global.exception.ErrorException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TopicService {

    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    private final TopicRepository topicRepository;
    private final TopicOptionRepository topicOptionRepository;
    private final VoteRepository voteRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public TopicResponse getTodayTopic(Long userId) {
        LocalDate today = currentKstDate();
        log.info(String.valueOf(today));

        Topic topic = topicRepository.findByTargetDateWithOptions(today)
                .orElseThrow(() -> new ErrorException(ErrorCode.TOPIC_NOT_FOUND));

        Long totalVotes = topic.getOptions().stream()
                .mapToLong(TopicOption::getVoteCount)
                .sum();

        Long votedOptionId = null;
        if (userId != null) {
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                Optional<Vote> vote = voteRepository.findByTopicAndUser(topic, user);
                if (vote.isPresent()) {
                    votedOptionId = vote.get().getSelectedOption().getOptionId();
                }
            }
        }

        Long finalTotalVotes = totalVotes;
        List<TopicOptionResponse> optionResponses = topic.getOptions().stream()
                .map(option -> new TopicOptionResponse(option, finalTotalVotes))
                .toList();

        return new TopicResponse(topic, optionResponses, totalVotes, votedOptionId);
    }

    @Transactional
    public void vote(Long topicId, VoteRequest request, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ErrorException(ErrorCode.USER_NOT_FOUND));

        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new ErrorException(ErrorCode.TOPIC_NOT_FOUND));

        if (topic.getStatus() != TopicStatus.OPEN && topic.getStatus() != TopicStatus.CLOSED) {
            throw new ErrorException(ErrorCode.TOPIC_NOT_FOUND);
        }

        if (!topic.getTargetDate().isEqual(currentKstDate())) {
            throw new ErrorException(ErrorCode.TOPIC_NOT_FOUND); // 혹은 적절한 에러 코드 (예: 투표 기간 아님)
        }

        if (voteRepository.existsByTopicAndUser(topic, user)) {
            throw new ErrorException(ErrorCode.DUPLICATE_VOTE);
        }

        TopicOption option = topicOptionRepository.findById(request.getOptionId())
                .orElseThrow(() -> new ErrorException(ErrorCode.OPTION_NOT_FOUND));

        if (!option.getTopic().getTopicId().equals(topicId)) {
            throw new ErrorException(ErrorCode.TOPIC_MISMATCH);
        }

        Vote vote = new Vote(topic, user, option);
        voteRepository.save(vote);
        
        topicOptionRepository.incrementVoteCount(option.getOptionId());
    }

    @Transactional
    public Long createTopic(CreateTopicRequest request) {
        if (request.getStatus() != TopicStatus.OPEN && request.getStatus() != TopicStatus.CLOSED) {
            throw new ErrorException(ErrorCode.VALIDATION_FAILED);
        }
        if (topicRepository.existsByTargetDate(request.getTargetDate())) {
            throw new ErrorException(ErrorCode.DUPLICATE_TOPIC_DATE);
        }

        TopicTitleNormalizer.Result title;
        try {
            title = TopicTitleNormalizer.normalize(request.getTitle());
        } catch (TopicTitleNormalizer.InvalidTitleException invalid) {
            throw new ErrorException(ErrorCode.VALIDATION_FAILED);
        }
        if (topicRepository.existsByNormalizedTitle(title.normalizedTitle())) {
            throw new ErrorException(ErrorCode.DUPLICATE_TOPIC);
        }

        Topic topic = new Topic(
            title.displayTitle(),
            request.getDescription(),
            request.getTargetDate(),
            request.getStatus()
        );
        topic.setNormalizedTitle(title.normalizedTitle());

        TopicOption optionA = new TopicOption(topic, OptionLabel.A, request.getOptionAText(), request.getOptionADescription());
        TopicOption optionB = new TopicOption(topic, OptionLabel.B, request.getOptionBText(), request.getOptionBDescription());

        topic.addOption(optionA);
        topic.addOption(optionB);

        try {
            topicRepository.saveAndFlush(topic);
        } catch (DataIntegrityViolationException failure) {
            if (TopicDuplicateKey.isNormalizedTitleDuplicate(failure)) {
                throw new ErrorException(ErrorCode.DUPLICATE_TOPIC);
            }
            throw failure;
        }
        return topic.getTopicId();
    }

    @Transactional
    public void updateTopic(Long topicId, UpdateTopicRequest request) {
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new ErrorException(ErrorCode.TOPIC_NOT_FOUND));

        if (topic.getStatus() != TopicStatus.OPEN && topic.getStatus() != TopicStatus.CLOSED) {
            throw new ErrorException(ErrorCode.TOPIC_NOT_FOUND);
        }
        if (request.getStatus() != null && request.getStatus() != TopicStatus.OPEN
                && request.getStatus() != TopicStatus.CLOSED) {
            throw new ErrorException(ErrorCode.VALIDATION_FAILED);
        }

        if (request.getTargetDate() != null && !request.getTargetDate().equals(topic.getTargetDate())) {
            if (topicRepository.existsByTargetDate(request.getTargetDate())) {
                throw new ErrorException(ErrorCode.DUPLICATE_TOPIC_DATE);
            }
        }

        String nextNormalizedTitle = null;
        String nextDisplayTitle = null;
        if (request.getTitle() != null) {
            try {
                var normalized = TopicTitleNormalizer.normalize(request.getTitle());
                nextNormalizedTitle = normalized.normalizedTitle();
                nextDisplayTitle = normalized.displayTitle();
            } catch (TopicTitleNormalizer.InvalidTitleException invalid) {
                throw new ErrorException(ErrorCode.VALIDATION_FAILED);
            }
            if (topicRepository.existsByNormalizedTitleAndTopicIdNot(nextNormalizedTitle, topicId)) {
                throw new ErrorException(ErrorCode.DUPLICATE_TOPIC);
            }
        }
        topic.update(request.getTitle(), request.getDescription(), request.getTargetDate(), request.getStatus());
        if (nextNormalizedTitle != null) topic.setTitleAndNormalizedTitle(nextDisplayTitle, nextNormalizedTitle);

        if (request.getOptionAText() != null || request.getOptionBText() != null || request.getOptionADescription() != null || request.getOptionBDescription() != null) {
            for (TopicOption option : topic.getOptions()) {
                if (option.getLabel() == OptionLabel.A) {
                    option.updateText(request.getOptionAText());
                    option.updateDescription(request.getOptionADescription());
                } else if (option.getLabel() == OptionLabel.B) {
                    option.updateText(request.getOptionBText());
                    option.updateDescription(request.getOptionBDescription());
                }
            }
        }
        try {
            topicRepository.flush();
        } catch (DataIntegrityViolationException failure) {
            if (TopicDuplicateKey.isNormalizedTitleDuplicate(failure)) {
                throw new ErrorException(ErrorCode.DUPLICATE_TOPIC);
            }
            throw failure;
        }
    }

    public Page<TopicListResponse> getTopicArchive(Pageable pageable) {
        return topicRepository.findAllByStatusIn(List.of(TopicStatus.OPEN, TopicStatus.CLOSED), pageable)
                .map(TopicListResponse::new);
    }

    private LocalDate currentKstDate() {
        // 인증과 공유하는 UTC Clock의 instant를 서비스 날짜 기준인 KST로 해석합니다.
        return LocalDate.now(clock.withZone(KOREA_ZONE));
    }
}
