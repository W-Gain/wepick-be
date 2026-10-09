package gguip1.community.domain.auth.attempt;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.regex.Pattern;

/** 시작 요청의 binding, 시도 ID, 복귀 경로를 공개 전에 검증합니다. */
@Component
public class LoginAttemptInputValidator {
    private static final Pattern BROWSER_BINDING_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern CLIENT_ATTEMPT_PATTERN = Pattern.compile("[A-Za-z0-9_-]{22,64}");
    private static final Pattern ENCODED_BYTE_PATTERN = Pattern.compile("%[0-9A-Fa-f]{2}");

    /** 32바이트를 패딩 없는 base64url 43자로 표현한 canonical browser binding만 받습니다. */
    public String validateBrowserBinding(String browserBinding) {
        if (browserBinding == null || !BROWSER_BINDING_PATTERN.matcher(browserBinding).matches()) {
            throw invalid("browserBinding");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(browserBinding);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(browserBinding)) {
                throw invalid("browserBinding");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid("browserBinding");
        }
        return browserBinding;
    }

    /** FE가 발급한 시도 ID를 URL 안전 ASCII 범위에서 원문 그대로 보존합니다. */
    public String validateClientAttemptId(String clientAttemptId) {
        if (clientAttemptId == null || !CLIENT_ATTEMPT_PATTERN.matcher(clientAttemptId).matches()) {
            throw invalid("attempt");
        }
        return clientAttemptId;
    }

    /** 상대 복귀 경로의 path만 한 번 정규화하고 query·fragment 의미는 보존합니다. */
    public String normalizeReturnTo(String returnTo) {
        if (returnTo == null) {
            throw invalid("returnTo");
        }
        if (returnTo.length() > 512 || containsUnsafeCharacter(returnTo)) {
            throw invalid("returnTo");
        }
        if (returnTo.startsWith("//")) {
            throw invalid("returnTo");
        }

        URI parsed;
        try {
            parsed = new URI(returnTo);
        } catch (URISyntaxException exception) {
            throw invalid("returnTo");
        }
        String originalRawPath = parsed.getRawPath();
        if (parsed.isAbsolute() || !originalRawPath.startsWith("/")) {
            throw invalid("returnTo");
        }

        URI normalized = parsed.normalize();
        String rawPath = normalized.getRawPath();
        String decodedPath = normalized.getPath();
        if (rawPath.startsWith("//") || decodedPath.startsWith("//")
                || containsUnsafeCharacter(decodedPath)
                || containsUnsafeCharacter(normalized.getQuery())
                || containsUnsafeCharacter(normalized.getFragment())
                || containsNestedEscape(decodedPath) || containsDotSegment(decodedPath)) {
            throw invalid("returnTo");
        }

        String canonical = normalized.toASCIIString();
        if (canonical.startsWith("//") || canonical.length() > 512) {
            throw invalid("returnTo");
        }
        return canonical;
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid " + field);
    }

    private static boolean containsUnsafeCharacter(String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' || Character.isISOControl(character)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNestedEscape(String decodedPath) {
        return ENCODED_BYTE_PATTERN.matcher(decodedPath).find();
    }

    private static boolean containsDotSegment(String decodedPath) {
        for (String segment : decodedPath.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
