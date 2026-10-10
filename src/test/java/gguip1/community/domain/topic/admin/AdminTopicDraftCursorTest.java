package gguip1.community.domain.topic.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminTopicDraftCursorTest {
    @Test
    @DisplayName("cursor는 UTC DATETIME(6) 미세초와 ID 정렬값을 손실 없이 왕복한다")
    void roundTripsMicrosecondsAndId() {
        var position = new AdminTopicDraftCursor.Position("DRAFT",
                LocalDateTime.parse("2026-10-10T12:34:56.123456"), 42L);

        var decoded = AdminTopicDraftCursor.decode(AdminTopicDraftCursor.encode(position), "DRAFT");

        assertThat(decoded).isEqualTo(position);
    }

    @Test
    @DisplayName("cursor는 endpoint와 status 필터가 다르거나 형식이 깨지면 거부한다")
    void rejectsScopeFilterAndMalformedTokens() {
        String token = AdminTopicDraftCursor.encode(new AdminTopicDraftCursor.Position("DRAFT",
                LocalDateTime.parse("2026-10-10T12:34:56.000001"), 7L));

        assertThatThrownBy(() -> AdminTopicDraftCursor.decode(token, "APPROVED"))
                .isInstanceOf(AdminTopicDraftException.class)
                .extracting(error -> ((AdminTopicDraftException) error).code()).isEqualTo("INVALID_CURSOR");
        assertThatThrownBy(() -> AdminTopicDraftCursor.decode(token + "=", "DRAFT"))
                .isInstanceOf(AdminTopicDraftException.class);
    }

    @Test
    @DisplayName("cursor는 overflow version·중복 또는 후행 JSON·DATETIME 범위 밖 시각을 거부한다")
    void rejectsStrictJsonAndDatabaseDatetimeBoundaries() {
        assertInvalid("{\"v\":4294967297,\"scope\":\"admin-topics\",\"status\":null,"
                + "\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":1}");
        assertInvalid("{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,"
                + "\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":1,\"topicId\":2}");
        assertInvalid("{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,"
                + "\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":1} {}");
        assertInvalid("{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,"
                + "\"createdAt\":\"0000-01-01T00:00:00.000000Z\",\"topicId\":1}");
    }

    @Test
    @DisplayName("cursor는 정확한 필드·정수 ID·UTF-8 형식과 MySQL DATETIME 경계를 지킨다")
    void validatesExactPayloadAndDatabaseBoundaries() {
        List<String> invalidJson = List.of(
                "[]",
                "{\"v\":1,\"scope\":\"admin-topics\",\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":1}",
                "{\"v\":1,\"scope\":\"admin-topics\",\"arbitrary\":null,\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":1}",
                "{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,\"createdAt\":\"2026-10-10T12:34:56.123456Z\"}",
                "{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"other\":1}",
                "{\"v\":2,\"scope\":\"admin-topics\",\"status\":null,\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":1}",
                "{\"v\":1,\"scope\":\"other-scope\",\"status\":null,\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":1}",
                "{\"v\":1.0,\"scope\":\"admin-topics\",\"status\":null,\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":1}",
                "{\"v\":1,\"scope\":\"admin-topics\",\"status\":1,\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":1}",
                "{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,\"createdAt\":3,\"topicId\":1}",
                "{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":1.0}",
                "{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":0}",
                "{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":-1}",
                "{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,\"createdAt\":\"2026-10-10T12:34:56.123456Z\",\"topicId\":9007199254740992}",
                "{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,\"createdAt\":\"0999-12-31T23:59:59.999999Z\",\"topicId\":1}",
                "{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,\"createdAt\":\"10000-01-01T00:00:00.000000Z\",\"topicId\":1}");
        invalidJson.forEach(AdminTopicDraftCursorTest::assertInvalid);

        assertThat(decodeJson("{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,"
                + "\"createdAt\":\"1000-01-01T00:00:00.000000Z\",\"topicId\":1}").createdAtUtc().getYear())
                .isEqualTo(1000);
        assertThat(decodeJson("{\"v\":1,\"scope\":\"admin-topics\",\"status\":null,"
                + "\"createdAt\":\"9999-12-31T23:59:59.999999Z\",\"topicId\":9007199254740991}")
                .topicId()).isEqualTo(9_007_199_254_740_991L);

        String malformedUtf8 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[]{(byte) 0xC3, 0x28});
        assertThatThrownBy(() -> AdminTopicDraftCursor.decode(malformedUtf8, null))
                .isInstanceOf(AdminTopicDraftException.class)
                .extracting(error -> ((AdminTopicDraftException) error).code()).isEqualTo("INVALID_CURSOR");
    }

    private static void assertInvalid(String json) {
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> AdminTopicDraftCursor.decode(token, null))
                .isInstanceOf(AdminTopicDraftException.class)
                .extracting(error -> ((AdminTopicDraftException) error).code()).isEqualTo("INVALID_CURSOR");
    }

    private static AdminTopicDraftCursor.Position decodeJson(String json) {
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return AdminTopicDraftCursor.decode(token, null);
    }
}
