# Topic (양자택일 투표) 기능 기획서

> **Superseded (2026-10-07):** 제품 방향 전환 이전의 기획입니다. "회원 전용, 당일만 투표" 등 현재 Accepted 정책과 다른 내용이 있어 구현 기준으로 사용하지 않습니다. 현재 기준은 [익명 투표·로그인 전환·어뷰징 정책](https://github.com/W-Gain/wepick-product/blob/main/docs/policies/anonymous-voting-login-and-abuse.md), [오늘의 Pick과 지난 Pick 운영 정책](https://github.com/W-Gain/wepick-product/blob/main/docs/policies/daily-pick-and-archive.md), [핵심 사용자 흐름과 MVP 범위](https://github.com/W-Gain/wepick-product/blob/main/docs/design/core-user-flows-and-mvp-scope.md)입니다.

## 1. 개요
WePick (Topic)은 **하루 1번 A/B 투표**를 중심으로 운영되는 서비스이며, 사용자는 매일 새로운 주제에 대해 투표하고 결과를 확인할 수 있습니다.

### 핵심 기능
- **오늘의 토픽 조회**: 매일 자정 갱신되는 토픽 조회
- **투표 하기**: A/B 중 하나를 선택하여 투표 (회원 전용, 1일 1회)
- **통계 확인**: 실시간 투표율 확인
- **아카이브**: 지난 토픽 목록 및 결과 조회

### 기술적 제약 사항
- **인증**: `gguip1.community.global.auth.annotation.Auth` 및 `SessionAuthFilter` 기반의 기존 인증 시스템 사용.
- **DB**: MySQL (JPA 사용), `BaseEntity` 상속.
- **패키지 구조**: `gguip1.community.domain.topic` 하위에 계층별 패키지 구성.

---

## 2. 패키지 및 파일 구조 계획

```
src/main/java/gguip1/community/domain/topic/
├── controller/
│   └── TopicController.java       # 토픽 조회, 투표 API
├── service/
│   └── TopicService.java          # 토픽 비즈니스 로직
├── repository/
│   ├── TopicRepository.java       # 토픽 DB 접근
│   └── VoteRepository.java        # 투표 DB 접근
├── entity/
│   ├── Topic.java                 # 토픽 엔티티
│   ├── TopicOption.java           # 토픽 선택지 (A/B)
│   └── Vote.java                  # 투표 내역
├── dto/
│   ├── request/
│   │   └── VoteRequest.java       # 투표 요청 DTO
│   └── response/
│   │   ├── TopicResponse.java     # 토픽 상세 응답 (통계 포함)
│   │   └── TopicListResponse.java # 토픽 목록 응답
└── exception/                     # (필요시) 도메인별 예외
```

---

## 3. 도메인 모델 설계 (Entity)

모든 엔티티는 `gguip1.community.global.entity.BaseEntity`를 상속하여 생성/수정 시간을 관리합니다.

### 3.1 Topic (토픽)
하루에 하나의 토픽이 발행됩니다.

```java
// gguip1.community.domain.topic.entity.Topic

@Entity
@Table(name = "topics")
public class Topic extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long topicId;

    @Column(nullable = false)
    private String title;          // 제목

    @Column(columnDefinition = "TEXT")
    private String description;    // 설명

    @Column(nullable = false)
    private LocalDate targetDate;  // 해당 토픽이 활성화되는 날짜

    @Enumerated(EnumType.STRING)
    private TopicStatus status;    // OPEN, CLOSED
    
    // OneToMany mappedBy = "topic" (TopicOption)
}
```

### 3.2 TopicOption (선택지)
각 토픽은 2개의 옵션(A, B)을 가집니다.

```java
// gguip1.community.domain.topic.entity.TopicOption

@Entity
@Table(name = "topic_options")
public class TopicOption {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long optionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_id")
    private Topic topic;

    @Enumerated(EnumType.STRING)
    private OptionLabel label;    // A, B

    private String text;          // 선택지 내용 (예: "짜장면")
    
    private Long voteCount;       // 통계 비정규화 (성능 최적화, 동시성 제어 필요)
}
```
* **Note**: `voteCount`를 `TopicOption`에 직접 두어 조회를 최적화합니다. 투표 시 `UPDATE topic_options SET vote_count = vote_count + 1 WHERE option_id = ?` 쿼리로 원자적 업데이트를 수행하거나, 별도 통계 테이블을 분리할 수 있습니다. (초기 단계에서는 단순화를 위해 컬럼 추가 방식 고려)

### 3.3 Vote (투표 내역)
유저당 1회 투표 제약을 위한 엔티티입니다.

```java
// gguip1.community.domain.topic.entity.Vote

@Entity
@Table(name = "votes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"topic_id", "user_id"}) // 유저당 토픽별 1회 투표 제한
})
public class Vote extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long voteId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_id")
    private Topic topic;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;            // gguip1.community.domain.user.entity.User

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id")
    private TopicOption selectedOption;
}
```

---

## 4. API 명세 (Draft)

공통 응답 포맷: `gguip1.community.global.response.ApiResponse<T>`

### 4.1 오늘의 토픽 조회
- **URL**: `GET /api/topics/today`
- **Response**: `ApiResponse<TopicResponse>`
- **Logic**:
    1. 오늘 날짜(`LocalDate.now()`)에 해당하는 `Topic`을 조회.
    2. `TopicOption` 및 현재 `voteCount` 정보를 포함하여 반환.
    3. 로그인한 유저의 경우, 이미 투표했는지 여부(`votedOptionId`)를 확인하여 응답에 포함.

### 4.2 투표하기
- **URL**: `POST /api/topics/{topicId}/vote`
- **Auth**: `@Auth` (필수)
- **Request**: `{ "optionId": 1 }`
- **Response**: `ApiResponse<Void>`
- **Logic**:
    1. 유저 인증 확인.
    2. 이미 투표했는지 `VoteRepository` 존재 여부 확인 (중복 투표 방지).
    3. `Vote` 엔티티 저장.
    4. `TopicOption.voteCount` 증가 (동시성 이슈 고려: DB 레벨 `UPDATE` 쿼리 사용).

### 4.3 토픽 아카이브 조회
- **URL**: `GET /api/topics`
- **Query Params**: `page=0`, `size=10`
- **Response**: `ApiResponse<List<TopicListResponse>>`
- **Logic**: 지난 날짜의 토픽들을 최신순으로 페이징 조회.

---

## 5. 구현 순서 (TODO)

1. **Domain Skeleton**: `gguip1.community.domain.topic` 패키지 생성 및 Entity 구현.
2. **Repository**: `TopicRepository`, `TopicOptionRepository`, `VoteRepository` 생성.
3. **Service (Business Logic)**:
    - 투표 로직 (중복 검사, 카운트 증가).
    - 조회 로직 (오늘의 토픽, 아카이브).
4. **Controller (API)**: API 엔드포인트 구현 및 DTO 매핑.
5. **Test**:
    - Repository 테스트 (DB 제약 조건 확인).
    - Service 단위 테스트 (투표 로직 검증).
    - Controller 통합 테스트.