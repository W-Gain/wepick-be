package gguip1.community.domain.auth.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** 기존 브라우저의 opaque 익명 식별 쿠키를 DB에는 SHA-256만 저장해 조회합니다. */
public final class AnonymousVoterCookie {
    public static final String PRODUCTION_NAME = "__Host-wepick-anon";
    public static final String LOCAL_NAME = "wepick-anon-local";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private AnonymousVoterCookie() {
    }

    public static String name(boolean secure) {
        return secure ? PRODUCTION_NAME : LOCAL_NAME;
    }

    public static String hashIfValid(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token)) {
                return null;
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (IllegalArgumentException | NoSuchAlgorithmException ignored) {
            return null;
        }
    }
}
