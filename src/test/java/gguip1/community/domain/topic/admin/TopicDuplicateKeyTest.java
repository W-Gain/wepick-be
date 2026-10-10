package gguip1.community.domain.topic.admin;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class TopicDuplicateKeyTest {
    @Test
    @DisplayName("정확한 normalized title UNIQUE key와 MySQL 1062 조합만 제목 중복으로 분류한다")
    void matchesOnlyExactMysqlIndexToken() {
        assertThat(TopicDuplicateKey.isNormalizedTitleDuplicate(new SQLException(
                "Duplicate entry 'x' for key 'UK_topics_normalized_title'", "23000", 1062))).isTrue();
        assertThat(TopicDuplicateKey.isNormalizedTitleDuplicate(new SQLException(
                "Duplicate entry 'x' for key 'topics.UK_topics_normalized_title'", "23000", 1062))).isTrue();
        assertThat(TopicDuplicateKey.isNormalizedTitleDuplicate(new SQLException(
                "Duplicate entry 'x' for key `db.topics.UK_topics_normalized_title`", "23000", 1062))).isTrue();
        assertThat(TopicDuplicateKey.isNormalizedTitleDuplicate(new SQLException(
                "Duplicate entry 'x' for key db.topics.UK_topics_normalized_title", "23000", 1062))).isTrue();
        assertThat(TopicDuplicateKey.isNormalizedTitleDuplicate(new SQLException(
                "Duplicate entry 'x' for key 'UK_topics_normalized_title_extra'", "23000", 1062))).isFalse();
        assertThat(TopicDuplicateKey.isNormalizedTitleDuplicate(new SQLException(
                "Duplicate entry 'x' for key 'other_table.UK_topics_normalized_title'", "23000", 1062))).isFalse();
        assertThat(TopicDuplicateKey.isNormalizedTitleDuplicate(new SQLException(
                "Duplicate entry 'x' for key 'UK_topics_normalized_title'", "23000", 1061))).isFalse();
        assertThat(TopicDuplicateKey.isNormalizedTitleDuplicate(new SQLException(
                "Duplicate entry 'x' without a key token", "23000", 1062))).isFalse();
        assertThat(TopicDuplicateKey.isNormalizedTitleDuplicate(new SQLException(
                "Duplicate entry 'x' for key 'db.other_table.UK_topics_normalized_title'", "23000", 1062))).isFalse();
        assertThat(TopicDuplicateKey.isNormalizedTitleDuplicate(new SQLException(null, "23000", 1062))).isFalse();
        assertThat(TopicDuplicateKey.isNormalizedTitleDuplicate(null)).isFalse();
        assertThat(TopicDuplicateKey.isNormalizedTitleDuplicate(new ConstraintViolationException(
                "duplicate key", new SQLException("duplicate", "23000", 1062),
                "UK_topics_normalized_title"))).isTrue();
    }
}
