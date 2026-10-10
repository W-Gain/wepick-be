package gguip1.community.domain.topic.admin;

import gguip1.community.domain.topic.admin.AdminTopicDraftCommand.Create;
import gguip1.community.domain.topic.admin.AdminTopicDraftCommand.Option;
import gguip1.community.domain.topic.admin.AdminTopicDraftCommand.Patch;
import gguip1.community.domain.topic.entity.OptionLabel;
import gguip1.community.domain.topic.entity.Topic;
import gguip1.community.domain.topic.entity.TopicOption;
import gguip1.community.domain.topic.entity.TopicStatus;
import gguip1.community.domain.topic.repository.TopicRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 초안 저장·revision·상태 이력을 하나의 트랜잭션 경계에서 처리합니다. */
@Service
@Transactional(readOnly = true)
public class AdminTopicDraftService {
    private static final long MAX_SAFE_ID = 9_007_199_254_740_991L;
    private static final List<TopicStatus> TARGET_STATUSES = List.of(TopicStatus.DRAFT, TopicStatus.APPROVED,
            TopicStatus.SCHEDULED, TopicStatus.PUBLISHED, TopicStatus.REJECTED, TopicStatus.HIDDEN);
    private static final DateTimeFormatter API_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final TopicRepository topics;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminTopicDraftService(TopicRepository topics, JdbcTemplate jdbc, Clock clock) {
        this.topics = topics;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public AdminTopicDraftViews.Detail create(long actorId, Create command) {
        requireSafeId(actorId);
        var title = normalizeTitle(command.title(), "title");
        AdminTopicCategory category = category(command.categoryCode(), "categoryCode");
        List<Option> options = validateOptions(command.options());
        if (topics.existsByNormalizedTitle(title.normalizedTitle())) {
            throw AdminTopicDraftException.duplicateTitle();
        }

        LocalDateTime nowUtc = utcNow();
        long topicId;
        try {
            topicId = insertTopic(title.displayTitle(), title.normalizedTitle(), category.name(), actorId, nowUtc);
            for (Option option : options) insertOption(topicId, option);
            insertStatusEvent(topicId, actorId, null, TopicStatus.DRAFT, nowUtc);
        } catch (DataIntegrityViolationException failure) {
            if (TopicDuplicateKey.isNormalizedTitleDuplicate(failure)) throw AdminTopicDraftException.duplicateTitle();
            throw failure;
        }
        return detail(topicId);
    }

    public AdminTopicDraftViews.Detail detail(long topicId) {
        requireSafeRequestId(topicId);
        Topic topic = topics.findTargetByIdWithOptions(topicId).orElseThrow(AdminTopicDraftException::notFound);
        return toDetail(topic);
    }

    public AdminTopicDraftViews.Page list(String statusFilter, String cursorToken, Integer requestedLimit) {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 50) throw AdminTopicDraftException.validation("limit", "INVALID_FORMAT");
        String status = parseStatusFilter(statusFilter);
        AdminTopicDraftCursor.Position cursor = cursorToken == null ? null
                : AdminTopicDraftCursor.decode(cursorToken, status);

        StringBuilder sql = new StringBuilder("SELECT topic_id,title,category_code,content_revision,status,created_at "
                + "FROM topics WHERE status IN ('DRAFT','APPROVED','SCHEDULED','PUBLISHED','REJECTED','HIDDEN')");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (cursor != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND topic_id < ?))");
            args.add(cursor.createdAtUtc());
            args.add(cursor.createdAtUtc());
            args.add(cursor.topicId());
        }
        sql.append(" ORDER BY created_at DESC, topic_id DESC LIMIT ?");
        args.add(limit + 1);
        List<ListRow> rows = jdbc.query(sql.toString(), (result, row) -> new ListRow(
                result.getLong("topic_id"), result.getString("title"), result.getString("category_code"),
                result.getInt("content_revision"), result.getString("status"),
                result.getObject("created_at", LocalDateTime.class)), args.toArray());

        boolean hasMore = rows.size() > limit;
        if (hasMore) rows.removeLast();
        List<AdminTopicDraftViews.ListItem> items = rows.stream().map(this::toListItem).toList();
        String nextCursor = hasMore && !rows.isEmpty()
                ? AdminTopicDraftCursor.encode(new AdminTopicDraftCursor.Position(status,
                rows.getLast().createdAtUtc(), rows.getLast().topicId())) : null;
        return new AdminTopicDraftViews.Page(items, nextCursor);
    }

    @Transactional
    public AdminTopicDraftViews.Detail patch(long topicId, long actorId, Patch command) {
        requireSafeRequestId(topicId);
        requireSafeId(actorId);
        Topic topic = topics.findByIdForUpdate(topicId).orElseThrow(AdminTopicDraftException::notFound);
        if (!TARGET_STATUSES.contains(topic.getStatus())) throw AdminTopicDraftException.notFound();
        if (!Objects.equals(topic.getContentRevision(), command.expectedRevision())) {
            throw AdminTopicDraftException.conflict("REVISION_CONFLICT", "토픽이 다른 내용으로 수정되었습니다.");
        }
        if (topic.getPublishedAt() != null || topic.getStatus() == TopicStatus.PUBLISHED
                || topic.getStatus() == TopicStatus.HIDDEN) {
            throw AdminTopicDraftException.conflict("INVALID_STATUS_TRANSITION", "현재 상태의 토픽은 수정할 수 없습니다.");
        }

        String nextTitle = topic.getTitle();
        String nextKey = topic.getNormalizedTitle();
        if (command.titlePresent()) {
            var normalized = normalizeTitle(command.title(), "title");
            nextTitle = normalized.displayTitle();
            nextKey = normalized.normalizedTitle();
        }
        String nextCategoryCode = topic.getCategoryCode();
        if (command.categoryPresent()) nextCategoryCode = category(command.categoryCode(), "categoryCode").name();

        List<TopicOption> currentOptions = topic.getOptions().stream()
                .sorted(Comparator.comparing(TopicOption::getLabel)).toList();
        List<Option> nextOptions = command.optionsPresent() ? validateOptions(command.options())
                : currentOptions.stream().map(option -> new Option(option.getLabel(), option.getText())).toList();
        boolean contentChanged = !Objects.equals(topic.getTitle(), nextTitle)
                || !Objects.equals(topic.getCategoryCode(), nextCategoryCode)
                || !sameOptions(currentOptions, nextOptions);
        if (!contentChanged) return toDetail(topic);

        if (topics.existsByNormalizedTitleAndTopicIdNot(nextKey, topicId)) {
            throw AdminTopicDraftException.duplicateTitle();
        }
        if (topic.getContentRevision() == Integer.MAX_VALUE) {
            throw AdminTopicDraftException.conflict("REVISION_CONFLICT", "토픽 revision 한도를 초과했습니다.");
        }
        TopicStatus previousStatus = topic.getStatus();
        TopicStatus nextStatus = previousStatus == TopicStatus.APPROVED || previousStatus == TopicStatus.SCHEDULED
                || previousStatus == TopicStatus.REJECTED
                ? TopicStatus.DRAFT : previousStatus;
        var nextScheduledDate = nextStatus == TopicStatus.DRAFT ? null : topic.getScheduledKstDate();
        topic.updateDraft(nextTitle, nextKey, nextCategoryCode, nextScheduledDate, nextStatus,
                topic.getContentRevision() + 1);
        for (int index = 0; index < currentOptions.size(); index++) {
            currentOptions.get(index).updateText(nextOptions.get(index).text());
        }

        try {
            topics.saveAndFlush(topic);
        } catch (DataIntegrityViolationException failure) {
            if (TopicDuplicateKey.isNormalizedTitleDuplicate(failure)) throw AdminTopicDraftException.duplicateTitle();
            throw failure;
        }
        if (previousStatus != nextStatus) {
            insertStatusEvent(topicId, actorId, previousStatus, nextStatus, utcNow());
        }
        return toDetail(topic);
    }

    private long insertTopic(String title, String normalizedTitle, String category, long creator, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator statement = connection -> {
            PreparedStatement prepared = connection.prepareStatement("""
                    INSERT INTO topics (title, normalized_title, category_code, content_revision, status,
                                        target_date, scheduled_kst_date, published_at, created_by_user_id, created_at)
                    VALUES (?, ?, ?, 1, 'DRAFT', NULL, NULL, NULL, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            prepared.setString(1, title);
            prepared.setString(2, normalizedTitle);
            prepared.setString(3, category);
            prepared.setLong(4, creator);
            prepared.setObject(5, createdAt);
            return prepared;
        };
        jdbc.update(statement, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return a topic ID");
        long topicId = key.longValue();
        requireSafeId(topicId);
        return topicId;
    }

    private void insertOption(long topicId, Option option) {
        jdbc.update("INSERT INTO topic_options (topic_id,label,text,vote_count) VALUES (?,?,?,0)",
                topicId, option.label().name(), option.text());
    }

    private void insertStatusEvent(long topicId, Long actorId, TopicStatus from, TopicStatus to, LocalDateTime occurredAt) {
        jdbc.update("INSERT INTO topic_status_events (topic_id,actor_user_id,from_status,to_status,occurred_at) "
                        + "VALUES (?,?,?,?,?)", topicId, actorId, from == null ? null : from.name(), to.name(), occurredAt);
    }

    private AdminTopicDraftViews.Detail toDetail(Topic topic) {
        requireSafeId(topic.getTopicId());
        AdminTopicCategory category = category(topic.getCategoryCode(), "categoryCode");
        Long creatorId = topic.getCreatedByUserId();
        if (creatorId != null) requireSafeId(creatorId);
        List<AdminTopicDraftViews.Option> options = topic.getOptions().stream()
                .sorted(Comparator.comparing(TopicOption::getLabel))
                .map(option -> new AdminTopicDraftViews.Option(option.getLabel().name(), option.getText())).toList();
        return new AdminTopicDraftViews.Detail(topic.getTopicId(), topic.getTitle(),
                new AdminTopicDraftViews.Category(category.name(), category.label()), options,
                topic.getContentRevision(), topic.getStatus().name(),
                topic.getScheduledKstDate() == null ? null : topic.getScheduledKstDate().toString(),
                apiTime(topic.getPublishedAt()), creatorId, apiTime(topic.getCreatedAt()));
    }

    private AdminTopicDraftViews.ListItem toListItem(ListRow row) {
        requireSafeId(row.topicId());
        AdminTopicCategory category = category(row.categoryCode(), "categoryCode");
        return new AdminTopicDraftViews.ListItem(row.topicId(), row.title(),
                new AdminTopicDraftViews.Category(category.name(), category.label()), row.contentRevision(), row.status());
    }

    private static boolean sameOptions(List<TopicOption> current, List<Option> next) {
        if (current.size() != 2 || next.size() != 2) return false;
        for (int index = 0; index < 2; index++) {
            if (current.get(index).getLabel() != next.get(index).label()
                    || !Objects.equals(current.get(index).getText(), next.get(index).text())) return false;
        }
        return true;
    }

    private static List<Option> validateOptions(List<Option> options) {
        if (options == null || options.size() != 2 || options.get(0).label() != OptionLabel.A
                || options.get(1).label() != OptionLabel.B) {
            throw AdminTopicDraftException.validation("options", "INVALID_FORMAT");
        }
        List<Option> normalized = new ArrayList<>(2);
        for (Option option : options) {
            try {
                normalized.add(new Option(option.label(), TopicTitleNormalizer.validateOptionText(option.text())));
            } catch (TopicTitleNormalizer.InvalidTitleException failure) {
                throw validationFor("options." + option.label().name().toLowerCase() + ".text", failure.violation());
            }
        }
        return List.copyOf(normalized);
    }

    private static TopicTitleNormalizer.Result normalizeTitle(String title, String field) {
        try {
            return TopicTitleNormalizer.normalize(title);
        } catch (TopicTitleNormalizer.InvalidTitleException failure) {
            throw validationFor(field, failure.violation());
        }
    }

    private static AdminTopicDraftException validationFor(String field, TopicTitleNormalizer.Violation violation) {
        return AdminTopicDraftException.validation(field, violation.name());
    }

    private static AdminTopicCategory category(String code, String field) {
        if (code == null || code.isBlank()) throw AdminTopicDraftException.validation(field, "REQUIRED");
        AdminTopicCategory category = AdminTopicCategory.fromCode(code);
        if (category == null) throw AdminTopicDraftException.validation(field, "INVALID_FORMAT");
        return category;
    }

    private static String parseStatusFilter(String value) {
        if (value == null) return null;
        for (TopicStatus status : TARGET_STATUSES) if (status.name().equals(value)) return value;
        throw AdminTopicDraftException.validation("status", "INVALID_FORMAT");
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String apiTime(LocalDateTime utcValue) {
        return utcValue == null ? null : utcValue.atOffset(ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.ofHours(9))
                .format(API_TIME);
    }

    private static void requireSafeId(long id) {
        if (id <= 0 || id > MAX_SAFE_ID) throw new IllegalStateException("Topic API ID is outside JSON safe integer range");
    }

    /** 요청 경로에 표현할 수 없는 ID는 저장소 내부 오류가 아니라 대상 부재로 응답합니다. */
    private static void requireSafeRequestId(long id) {
        if (id <= 0 || id > MAX_SAFE_ID) throw AdminTopicDraftException.notFound();
    }

    private record ListRow(long topicId, String title, String categoryCode, int contentRevision,
                           String status, LocalDateTime createdAtUtc) {
    }
}
