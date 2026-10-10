package gguip1.community.domain.auth.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class MemberNicknameGeneratorTest {
    @Test
    @DisplayName("80비트 난수를 RFC 4648 Base32로 손실 없이 변환한다")
    void encodesFixedEightyBitVectorWithoutLosingBoundaryBits() {
        SecureRandom fixed = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) index;
            }
        };

        assertThat(new MemberNicknameGenerator(fixed).generate())
                .isEqualTo("위픽-aaaqeayeaudaocaj");
    }

    @Test
    @DisplayName("80개의 1비트는 Base32 문자 7 열여섯 개가 된다")
    void encodesAllOneBits() {
        SecureRandom fixed = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                java.util.Arrays.fill(bytes, (byte) 0xff);
            }
        };

        assertThat(new MemberNicknameGenerator(fixed).generate())
                .isEqualTo("위픽-7777777777777777");
    }
}
