package gguip1.community.domain.topic.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicTitleNormalizerTest {
    @Test
    @DisplayName("NFC와 Unicode White_Space 및 지정 문장부호를 같은 중복 키로 접는다")
    void normalizesCanonicalWhitespaceAndSelectedPunctuation() {
        var result = TopicTitleNormalizer.normalize("  cafe\u0301\u00a0\u00a0‘한글’—질문  ");

        assertThat(result.displayTitle()).isEqualTo("cafe\u0301\u00a0\u00a0‘한글’—질문");
        assertThat(result.normalizedTitle()).isEqualTo("café '한글'-질문");
    }

    @Test
    @DisplayName("다른 기호와 대소문자 및 유효한 이모지는 보존하고 코드 포인트로 센다")
    void preservesOtherSymbolsAndCountsSupplementaryCharacters() {
        var result = TopicTitleNormalizer.normalize("😀 + / − % # @ (A) ?!");

        assertThat(result.normalizedTitle()).isEqualTo("😀 + / − % # @ (A) ?!");
        assertThat(TopicTitleNormalizer.codePointCount("😀".repeat(255))).isEqualTo(255);
    }

    @Test
    @DisplayName("고립 surrogate와 256 코드 포인트는 저장 전에 분류해 거부한다")
    void rejectsMalformedUnicodeAndOverlongInput() {
        assertThatThrownBy(() -> TopicTitleNormalizer.normalize("질문\uD800"))
                .isInstanceOf(TopicTitleNormalizer.InvalidTitleException.class)
                .extracting(error -> ((TopicTitleNormalizer.InvalidTitleException) error).violation())
                .isEqualTo(TopicTitleNormalizer.Violation.INVALID_FORMAT);
        assertThatThrownBy(() -> TopicTitleNormalizer.normalize("😀".repeat(256)))
                .isInstanceOf(TopicTitleNormalizer.InvalidTitleException.class)
                .extracting(error -> ((TopicTitleNormalizer.InvalidTitleException) error).violation())
                .isEqualTo(TopicTitleNormalizer.Violation.TOO_LONG);
        assertThat(TopicTitleNormalizer.validateOptionText("😀".repeat(255))).hasSize(510);
    }

    @Test
    @DisplayName("Unicode 바깥 공백을 먼저 제거한 표시값의 코드 포인트 길이만 검사한다")
    void trimsBeforeCheckingCodePointBoundaries() {
        String padded255 = "\u00a0" + "😀".repeat(255) + "\u3000";
        var normalized = TopicTitleNormalizer.normalize(padded255);

        assertThat(normalized.displayTitle()).isEqualTo("😀".repeat(255));
        assertThat(normalized.normalizedTitle()).isEqualTo("😀".repeat(255));
        assertThat(TopicTitleNormalizer.validateOptionText(padded255)).isEqualTo("😀".repeat(255));
        assertThatThrownBy(() -> TopicTitleNormalizer.normalize(" " + "😀".repeat(256) + " "))
                .isInstanceOf(TopicTitleNormalizer.InvalidTitleException.class)
                .extracting(error -> ((TopicTitleNormalizer.InvalidTitleException) error).violation())
                .isEqualTo(TopicTitleNormalizer.Violation.TOO_LONG);
        assertThatThrownBy(() -> TopicTitleNormalizer.validateOptionText(" " + "😀".repeat(256) + " "))
                .isInstanceOf(TopicTitleNormalizer.InvalidTitleException.class)
                .extracting(error -> ((TopicTitleNormalizer.InvalidTitleException) error).violation())
                .isEqualTo(TopicTitleNormalizer.Violation.TOO_LONG);
        assertThatThrownBy(() -> TopicTitleNormalizer.normalize("\u2000".repeat(256)))
                .isInstanceOf(TopicTitleNormalizer.InvalidTitleException.class)
                .extracting(error -> ((TopicTitleNormalizer.InvalidTitleException) error).violation())
                .isEqualTo(TopicTitleNormalizer.Violation.REQUIRED);
        assertThatThrownBy(() -> TopicTitleNormalizer.validateOptionText("\u2000".repeat(256)))
                .isInstanceOf(TopicTitleNormalizer.InvalidTitleException.class)
                .extracting(error -> ((TopicTitleNormalizer.InvalidTitleException) error).violation())
                .isEqualTo(TopicTitleNormalizer.Violation.REQUIRED);
        assertViolation("a" + " ".repeat(300) + "b", TopicTitleNormalizer.Violation.TOO_LONG);
        assertViolation("\u0958".repeat(128), TopicTitleNormalizer.Violation.TOO_LONG);
    }

    @Test
    @DisplayName("정의된 Unicode White_Space 전체를 trim·축약하고 인접한 비공백은 보존한다")
    void handlesUnicodeWhiteSpaceSetAndNeighboringCharacters() {
        int[] whiteSpace = {0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0,
                0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
                0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000};
        for (int codePoint : whiteSpace) {
            String separator = new String(Character.toChars(codePoint));
            assertThat(TopicTitleNormalizer.normalize("x" + separator + "y").normalizedTitle())
                    .as("U+%04X is collapsed", codePoint).isEqualTo("x y");
            assertThat(TopicTitleNormalizer.normalize(separator + "x" + separator).displayTitle())
                    .as("U+%04X is trimmed", codePoint).isEqualTo("x");
        }
        for (int codePoint : new int[]{0x0008, 0x000E, 0x001F, 0x1FFF, 0x200B}) {
            String neighboring = new String(Character.toChars(codePoint));
            assertThat(TopicTitleNormalizer.normalize("x" + neighboring + "y").normalizedTitle())
                    .as("U+%04X is preserved", codePoint).isEqualTo("x" + neighboring + "y");
        }
    }

    @Test
    @DisplayName("null·고립 high/low surrogate는 제목과 옵션에서 REQUIRED 또는 INVALID_FORMAT이다")
    void rejectsNullAndEveryMalformedSurrogateShape() {
        assertViolation(null, TopicTitleNormalizer.Violation.REQUIRED);
        assertViolation("bad\uD800x", TopicTitleNormalizer.Violation.INVALID_FORMAT);
        assertViolation("\uDC00bad", TopicTitleNormalizer.Violation.INVALID_FORMAT);
        assertThatThrownBy(() -> TopicTitleNormalizer.validateOptionText("bad\uD800x"))
                .isInstanceOf(TopicTitleNormalizer.InvalidTitleException.class)
                .extracting(error -> ((TopicTitleNormalizer.InvalidTitleException) error).violation())
                .isEqualTo(TopicTitleNormalizer.Violation.INVALID_FORMAT);
        assertThatThrownBy(() -> TopicTitleNormalizer.validateOptionText(null))
                .isInstanceOf(TopicTitleNormalizer.InvalidTitleException.class)
                .extracting(error -> ((TopicTitleNormalizer.InvalidTitleException) error).violation())
                .isEqualTo(TopicTitleNormalizer.Violation.REQUIRED);
    }

    private static void assertViolation(String title, TopicTitleNormalizer.Violation violation) {
        assertThatThrownBy(() -> TopicTitleNormalizer.normalize(title))
                .isInstanceOf(TopicTitleNormalizer.InvalidTitleException.class)
                .extracting(error -> ((TopicTitleNormalizer.InvalidTitleException) error).violation())
                .isEqualTo(violation);
    }
}
