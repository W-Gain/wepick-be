package gguip1.community.domain.topic.repository;

import gguip1.community.domain.topic.entity.Topic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

public interface TopicRepository extends JpaRepository<Topic, Long> {
    
    boolean existsByTargetDate(LocalDate targetDate);

    boolean existsByNormalizedTitle(String normalizedTitle);

    boolean existsByNormalizedTitleAndTopicIdNot(String normalizedTitle, Long topicId);

    Page<Topic> findAllByStatusIn(Collection<gguip1.community.domain.topic.entity.TopicStatus> statuses,
                                  Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Topic t WHERE t.topicId = :topicId")
    Optional<Topic> findByIdForUpdate(@Param("topicId") Long topicId);

    @Query("SELECT t FROM Topic t LEFT JOIN FETCH t.options WHERE t.topicId = :topicId "
            + "AND t.status IN (gguip1.community.domain.topic.entity.TopicStatus.DRAFT, "
            + "gguip1.community.domain.topic.entity.TopicStatus.APPROVED, "
            + "gguip1.community.domain.topic.entity.TopicStatus.SCHEDULED, "
            + "gguip1.community.domain.topic.entity.TopicStatus.PUBLISHED, "
            + "gguip1.community.domain.topic.entity.TopicStatus.REJECTED, "
            + "gguip1.community.domain.topic.entity.TopicStatus.HIDDEN)")
    Optional<Topic> findTargetByIdWithOptions(@Param("topicId") Long topicId);

    @Query("SELECT t FROM Topic t LEFT JOIN FETCH t.options WHERE t.targetDate = :targetDate "
            + "AND t.status IN (gguip1.community.domain.topic.entity.TopicStatus.OPEN, "
            + "gguip1.community.domain.topic.entity.TopicStatus.CLOSED)")
    Optional<Topic> findByTargetDateWithOptions(@Param("targetDate") LocalDate targetDate);
}
