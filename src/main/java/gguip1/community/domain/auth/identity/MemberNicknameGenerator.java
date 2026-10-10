package gguip1.community.domain.auth.identity;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Kakao 프로필을 수집하지 않고 WePick 전용 80-bit 무작위 닉네임을 만듭니다. */
@Component
public class MemberNicknameGenerator {
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private final SecureRandom random;

    public MemberNicknameGenerator(SecureRandom random) {
        this.random = random;
    }

    public String generate() {
        byte[] bytes = new byte[10];
        random.nextBytes(bytes);
        StringBuilder suffix = new StringBuilder(16);
        long buffer = 0;
        int bitsLeft = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                suffix.append(BASE32[(int) ((buffer >> bitsLeft) & 0x1f)]);
                buffer &= (1L << bitsLeft) - 1;
            }
        }
        if (bitsLeft > 0) suffix.append(BASE32[(int) ((buffer << (5 - bitsLeft)) & 0x1f)]);
        return "위픽-" + suffix;
    }
}
