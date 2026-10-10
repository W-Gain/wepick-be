package gguip1.community.domain.topic.entity;

import gguip1.community.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "topics")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Topic extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long topicId;

    @Column(nullable = false)
    private String title;

    @Column(name = "normalized_title", nullable = false, length = 255)
    private String normalizedTitle;

    @Column(name = "category_code", length = 30)
    private String categoryCode;

    @Column(name = "content_revision", nullable = false)
    private Integer contentRevision = 1;

    @Column(name = "scheduled_kst_date")
    private LocalDate scheduledKstDate;

    @Column(name = "published_at")
    private java.time.LocalDateTime publishedAt;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TopicStatus status;

    @OneToMany(mappedBy = "topic", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TopicOption> options = new ArrayList<>();

    public Topic(String title, String description, LocalDate targetDate, TopicStatus status) {
        this.title = title;
        this.description = description;
        this.targetDate = targetDate;
        this.status = status;
    }

    /** Legacy topic 저장 시에도 V5 제목 UNIQUE 키를 함께 채웁니다. */
    public void setNormalizedTitle(String normalizedTitle) {
        this.normalizedTitle = normalizedTitle;
    }

    public void setTitleAndNormalizedTitle(String title, String normalizedTitle) {
        this.title = title;
        this.normalizedTitle = normalizedTitle;
    }

    /** DRAFT 생성 입력을 기존 OPEN/CLOSED 생성 경로와 구분해 초기화합니다. */
    public void initializeDraft(String normalizedTitle, String categoryCode, Long createdByUserId) {
        this.normalizedTitle = normalizedTitle;
        this.categoryCode = categoryCode;
        this.contentRevision = 1;
        this.scheduledKstDate = null;
        this.publishedAt = null;
        this.createdByUserId = createdByUserId;
        this.targetDate = null;
        this.status = TopicStatus.DRAFT;
    }

    /** 수정은 서비스가 잠근 행의 revision을 확인한 뒤 기존 A/B 행만 갱신합니다. */
    public void updateDraft(String title, String normalizedTitle, String categoryCode,
                            LocalDate scheduledKstDate, TopicStatus status, int revision) {
        this.title = title;
        this.normalizedTitle = normalizedTitle;
        this.categoryCode = categoryCode;
        this.scheduledKstDate = scheduledKstDate;
        this.status = status;
        this.contentRevision = revision;
    }

    public void addOption(TopicOption option) {
        this.options.add(option);
    }

    public void update(String title, String description, LocalDate targetDate, TopicStatus status) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (targetDate != null) this.targetDate = targetDate;
        if (status != null) this.status = status;
    }
}
