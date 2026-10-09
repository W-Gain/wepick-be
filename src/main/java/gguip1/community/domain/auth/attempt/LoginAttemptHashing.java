package gguip1.community.domain.auth.attempt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 생성·소비가 공유하는 소문자 SHA-256 hex 표기를 제공합니다. */
final class LoginAttemptHashing {
    private LoginAttemptHashing() {
    }

    /** UTF-8 원문을 SHA-256으로 해시해 64자 소문자 hex로 반환합니다. */
    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
