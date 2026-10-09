package gguip1.community.domain.auth.attempt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LoginAttemptInputValidatorTest {
    private final LoginAttemptInputValidator validator = new LoginAttemptInputValidator();

    @Test
    @DisplayName("정규 결속값과 허용된 시도 ID를 원문 그대로 받는다")
    void acceptsCanonicalBindingAndPreservesUrlSafeAttemptId() {
        String binding = "A".repeat(43);
        String uuid = "f8c8fc69-5b88-4f14-8f30-a0e7df481bb0";
        String mixedCase = "AbCdEfGhIjKlMnOpQrStUvWxYz_0123456789-";

        assertThat(validator.validateBrowserBinding(binding)).isEqualTo(binding);
        assertThat(validator.validateClientAttemptId(uuid)).isEqualTo(uuid);
        assertThat(validator.validateClientAttemptId(mixedCase)).isEqualTo(mixedCase);
    }

    @Test
    @DisplayName("결속값의 누락·잘못된 길이·비정규 base64url을 거부한다")
    void rejectsMissingMalformedOrNonCanonicalBinding() {
        for (String invalid : List.of("", "A".repeat(42), "A".repeat(42) + "B", "A".repeat(42) + "=", " ")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> validator.validateBrowserBinding(invalid));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validateBrowserBinding(null));
    }

    @Test
    @DisplayName("시도 ID를 URL 안전 ASCII 22~64자로 제한한다")
    void restrictsAttemptIdToUrlSafeAsciiBetween22And64Characters() {
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validateClientAttemptId(null));
        String minimumLength = "A".repeat(22);
        String maximumLength = "A".repeat(64);
        assertThat(validator.validateClientAttemptId(minimumLength)).isEqualTo(minimumLength);
        assertThat(validator.validateClientAttemptId(maximumLength)).isEqualTo(maximumLength);
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validateClientAttemptId("a".repeat(21)));
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validateClientAttemptId("a".repeat(65)));
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validateClientAttemptId("a".repeat(21) + "+"));
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validateClientAttemptId("한".repeat(22)));
    }

    @Test
    @DisplayName("경로만 정규화하고 query·fragment·더하기 기호를 보존한다")
    void normalizesOnlyThePathAndPreservesQueryFragmentAndPlus() {
        assertThat(validator.normalizeReturnTo("/picks/one/../two?text=a+b&encoded=%26#overview"))
                .isEqualTo("/picks/two?text=a+b&encoded=%26#overview");
        assertThat(validator.normalizeReturnTo("/picks/safe%2Fsegment?plus=+&reserved=%26%3D#top"))
                .isEqualTo("/picks/safe%2Fsegment?plus=+&reserved=%26%3D#top");
        assertThat(validator.normalizeReturnTo("/.//x?plus=+#top"))
                .isEqualTo("/x?plus=+#top");
        assertThat(validator.normalizeReturnTo("/주제/한글?검색=서울+카페&표시=%26%3D#요약"))
                .isEqualTo("/%EC%A3%BC%EC%A0%9C/%ED%95%9C%EA%B8%80?%EA%B2%80%EC%83%89=%EC%84%9C%EC%9A%B8+%EC%B9%B4%ED%8E%98&%ED%91%9C%EC%8B%9C=%26%3D#%EC%9A%94%EC%95%BD");
        assertThat(validator.normalizeReturnTo("/safe%25gg")).isEqualTo("/safe%25gg");
        assertThat(validator.normalizeReturnTo("/literal%25")).isEqualTo("/literal%25");
        assertThat(validator.normalizeReturnTo("/" + "x".repeat(511))).hasSize(512);
    }

    @Test
    @DisplayName("외부 이동·위험 문자·중첩 인코딩·길이 초과를 값 노출 없이 거부한다")
    void rejectsUnsafeReturnToWithoutEchoingInputInTheException() {
        List<String> invalidValues = List.of(
                "",
                "https://evil.example/path",
                "//evil.example/path",
                "///evil.example/path",
                "////evil.example/path",
                "relative/path",
                "/%2F%2Fevil.example/path",
                "/%252F%252Fevil.example/path",
                "/safe%252F",
                "/%252e%252e/secret",
                "/safe%2500",
                "/safe%2509",
                "/safe%250A",
                "/safe%250F",
                "/safe%25af",
                "/safe%25aF",
                "/safe%2530",
                "/safe%2539",
                "/safe/%2e%2e/%2F%2Fevil.example",
                "/a\\\\evil.example",
                "/bad%0Apath",
                "/safe?query=%0D",
                "/safe#fragment%7F",
                "/bad\npath",
                "/" + "x".repeat(512),
                "/" + "x".repeat(513) + "/../ok",
                "/a/../".repeat(90) + "ok");
        for (int index = 0; index < invalidValues.size(); index++) {
            String invalid = invalidValues.get(index);
            var assertion = assertThatIllegalArgumentException()
                    .as("invalid returnTo case %s", index)
                    .isThrownBy(() -> validator.normalizeReturnTo(invalid));
            if (!invalid.isEmpty()) {
                assertion.withMessageNotContaining(invalid);
            }
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validator.normalizeReturnTo("/" + "é".repeat(200)));
        assertThatIllegalArgumentException().isThrownBy(() -> validator.normalizeReturnTo(null));
        assertThatIllegalArgumentException().isThrownBy(() -> validator.normalizeReturnTo(" \t"));
    }
}
