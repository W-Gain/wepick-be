package gguip1.community.global.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NicknamePolicyTest {
    @Test
    @DisplayName("앞뒤 Unicode 공백은 정리하고 code point 수로 길이를 센다")
    void trimsUnicodeEdgesAndCountsCodePoints() {
        String nickname = "\u2003" + "🙂".repeat(30) + "\u2003";

        assertThat(NicknamePolicy.normalize(nickname)).isEqualTo("🙂".repeat(30));
        assertThat(NicknamePolicy.normalize("가")).isEqualTo("가");
        assertThat(NicknamePolicy.normalize("\u00a0가\u00a0")).isEqualTo("가");
    }

    @Test
    @DisplayName("내부 공백·제어문자·빈값·31 code point는 거부한다")
    void rejectsWhitespaceControlAndOutOfRangeNicknames() {
        for (String invalid : new String[]{"가 나", "가\t나", "\u0001가", " \u2003 ", "가".repeat(31)}) {
            assertThatThrownBy(() -> NicknamePolicy.normalize(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid nickname");
        }
        assertThatThrownBy(() -> NicknamePolicy.normalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid nickname");
        assertThatThrownBy(() -> NicknamePolicy.normalize("가\u00a0나"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid nickname");
    }

    @Test
    @DisplayName("nullable DTO fields stay optional while supplied nicknames use the shared format rule")
    void validityHelperMatchesOptionalAndSuppliedNicknameRules() {
        assertThat(NicknamePolicy.isValid(null)).isTrue();
        assertThat(NicknamePolicy.isValid("가")).isTrue();
        assertThat(NicknamePolicy.isValid("🙂".repeat(30))).isTrue();
        assertThat(NicknamePolicy.isValid("가 나")).isFalse();
        assertThat(NicknamePolicy.isValid("🙂".repeat(31))).isFalse();
        assertThat(NicknamePolicy.isValid("\u0001가")).isFalse();
    }
}
