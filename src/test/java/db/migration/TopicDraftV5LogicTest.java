package db.migration;

import gguip1.community.domain.topic.admin.TopicTitleNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicDraftV5LogicTest {
    @Test
    @DisplayName("V5 사전검증은 모든 상태의 제목 키를 고정 규칙으로 계산하고 기존 키와 대조한다")
    void preflightsAllRowsBeforeMigrationChanges() {
        var result = TopicDraftV5Logic.preflight(List.of(
                new TopicDraftV5Logic.Row(1, "  cafe\u0301—질문 ", null),
                new TopicDraftV5Logic.Row(2, "다른 질문", "다른 질문")));

        assertThat(result.normalizedKeys()).containsEntry(1L, "café-질문").containsEntry(2L, "다른 질문");
        TopicDraftV5Logic.requireBackfilled(List.of(new TopicDraftV5Logic.Row(1, "  cafe\u0301—질문 ", "café-질문"),
                new TopicDraftV5Logic.Row(2, "다른 질문", "다른 질문")), result);
    }

    @Test
    @DisplayName("V5는 정규화 충돌과 기존 키 불일치를 어떤 변경 전에도 거부한다")
    void rejectsCollisionsAndMismatchedStoredKeys() {
        assertThatThrownBy(() -> TopicDraftV5Logic.preflight(List.of(
                new TopicDraftV5Logic.Row(1, "질문—하나", null),
                new TopicDraftV5Logic.Row(2, "질문–하나", null))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> TopicDraftV5Logic.preflight(List.of(
                new TopicDraftV5Logic.Row(1, "제목", "잘못된 키"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mismatch");
    }

    @Test
    @DisplayName("V5는 문서화한 schema prefix만 재개하고 예상 밖 상태는 중단한다")
    void classifiesOnlyKnownSchemaPrefixes() {
        var before = new TopicDraftV5Logic.Schema(TopicDraftV5Logic.LEGACY_STATUS_TYPE,
                false, "utf8mb4", "utf8mb4_0900_ai_ci", true, false);
        var expanded = new TopicDraftV5Logic.Schema(TopicDraftV5Logic.FINAL_STATUS_TYPE,
                true, "utf8mb4", "utf8mb4_0900_ai_ci", true, false);
        var collation = new TopicDraftV5Logic.Schema(TopicDraftV5Logic.FINAL_STATUS_TYPE,
                true, "utf8mb4", TopicDraftV5Logic.TARGET_COLLATION, true, false);
        var finalPrefix = new TopicDraftV5Logic.Schema(TopicDraftV5Logic.FINAL_STATUS_TYPE,
                true, "utf8mb4", TopicDraftV5Logic.TARGET_COLLATION, false, false);
        var complete = new TopicDraftV5Logic.Schema(TopicDraftV5Logic.FINAL_STATUS_TYPE,
                true, "utf8mb4", TopicDraftV5Logic.TARGET_COLLATION, false, true);

        assertThat(TopicDraftV5Logic.classify(before, "utf8mb4_0900_ai_ci"))
                .isEqualTo(TopicDraftV5Logic.Phase.BEFORE);
        assertThat(TopicDraftV5Logic.classify(expanded, "utf8mb4_0900_ai_ci"))
                .isEqualTo(TopicDraftV5Logic.Phase.EXPANDED);
        assertThat(TopicDraftV5Logic.classify(collation, "utf8mb4_0900_ai_ci"))
                .isEqualTo(TopicDraftV5Logic.Phase.KEY_COLLATION);
        assertThat(TopicDraftV5Logic.classify(finalPrefix, "utf8mb4_0900_ai_ci"))
                .isEqualTo(TopicDraftV5Logic.Phase.NOT_NULL);
        assertThat(TopicDraftV5Logic.classify(complete, "utf8mb4_0900_ai_ci"))
                .isEqualTo(TopicDraftV5Logic.Phase.COMPLETE);
        assertThatThrownBy(() -> TopicDraftV5Logic.classify(
                new TopicDraftV5Logic.Schema("enum('OPEN')", true, "utf8mb4",
                        TopicDraftV5Logic.TARGET_COLLATION, true, false), "utf8mb4_0900_ai_ci"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TopicDraftV5Logic.classify(
                new TopicDraftV5Logic.Schema(TopicDraftV5Logic.FINAL_STATUS_TYPE, true, "latin1",
                        TopicDraftV5Logic.TARGET_COLLATION, true, false), "utf8mb4_0900_ai_ci"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TopicDraftV5Logic.classify(
                new TopicDraftV5Logic.Schema(null, true, "utf8mb4",
                        TopicDraftV5Logic.TARGET_COLLATION, true, false), "utf8mb4_0900_ai_ci"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TopicDraftV5Logic.classify(
                new TopicDraftV5Logic.Schema(TopicDraftV5Logic.FINAL_STATUS_TYPE, true, "utf8mb4",
                        null, true, false), "utf8mb4_0900_ai_ci"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TopicDraftV5Logic.classify(expanded, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TopicDraftV5Logic.requireBackfilled(
                List.of(new TopicDraftV5Logic.Row(1, "제목", null)),
                new TopicDraftV5Logic.Preflight(java.util.Map.of(1L, "제목"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
        assertThatThrownBy(() -> TopicDraftV5Logic.requireBackfilled(
                List.of(new TopicDraftV5Logic.Row(1, "제목", "다른 키")),
                new TopicDraftV5Logic.Preflight(java.util.Map.of(1L, "제목"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
    }

    @Test
    @DisplayName("V5 고정 v1 정규화 키는 동일 fixture의 runtime 결과와 UTF-8 바이트까지 같다")
    void frozenMigrationNormalizerMatchesRuntimeFixtures() {
        List<String> fixtures = List.of(
                " \u00a0cafe\u0301\u2002‘한글’—질문\u3000",
                " - case + / − % # @ (A) ?! 😀 ",
                "\u00a0" + "😀".repeat(255) + "\u3000",
                "x\t\n\u000b\u000c\r \u0085\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005"
                        + "\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000y");

        for (String fixture : fixtures) {
            byte[] runtime = TopicTitleNormalizer.normalize(fixture).normalizedTitle().getBytes(StandardCharsets.UTF_8);
            byte[] migration = TopicDraftV5Logic.normalizeV1Title(fixture).getBytes(StandardCharsets.UTF_8);
            assertThat(migration).containsExactly(runtime);
        }
        int[] whiteSpace = {0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0,
                0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
                0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000};
        for (int codePoint : whiteSpace) {
            String separator = new String(Character.toChars(codePoint));
            assertThat(TopicDraftV5Logic.normalizeV1Title("x" + separator + "y"))
                    .as("V5 U+%04X collapses to one space", codePoint).isEqualTo("x y");
            assertThat(TopicDraftV5Logic.normalizeV1Title(separator + "x" + separator))
                    .as("V5 U+%04X trims at both edges", codePoint).isEqualTo("x");
        }
        for (int codePoint : new int[]{0x0008, 0x000E, 0x001F, 0x1FFF, 0x200B}) {
            String neighboring = new String(Character.toChars(codePoint));
            assertThat(TopicDraftV5Logic.normalizeV1Title("x" + neighboring + "y"))
                    .as("V5 U+%04X remains unchanged", codePoint).isEqualTo("x" + neighboring + "y");
        }
        assertThatThrownBy(() -> TopicDraftV5Logic.normalizeV1Title("\u2000".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TopicDraftV5Logic.normalizeV1Title("😀".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TopicDraftV5Logic.normalizeV1Title("a" + " ".repeat(300) + "b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TopicDraftV5Logic.normalizeV1Title("\u0958".repeat(128)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TopicDraftV5Logic.normalizeV1Title("bad\uD800"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TopicDraftV5Logic.normalizeV1Title("bad\uD800x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TopicDraftV5Logic.normalizeV1Title("\uDC00bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TopicDraftV5Logic.normalizeV1Title(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
