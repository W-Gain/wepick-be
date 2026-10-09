package gguip1.community.domain.auth.start;

import gguip1.community.domain.auth.attempt.LoginAttemptInputValidator;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/** 세션 ID와 분리된 확인 값을 준비하고, 시작 시에는 이미 준비된 값만 사용합니다. */
@Component
public class LoginBrowserBindingCookie {
    private final KakaoAuthorizationProperties properties;
    private final LoginAttemptInputValidator validator;
    private final SecureRandom random;

    public LoginBrowserBindingCookie(KakaoAuthorizationProperties properties,
                                    LoginAttemptInputValidator validator, SecureRandom random) {
        this.properties = properties;
        this.validator = validator;
        this.random = random;
    }

    /** 준비만 직렬화한 FE가 쿠키 저장까지 기다린 뒤 각 탭에서 start를 호출합니다. */
    public String prepare(HttpServletRequest request) {
        String existing = singleValue(request);
        if (valid(existing)) {
            return existing;
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 첫 방문의 동시 start에서 서로 다른 Set-Cookie를 발급하는 경합을 막습니다. */
    public String requirePrepared(HttpServletRequest request) {
        String existing = singleValue(request);
        if (existing == null) {
            throw invalid("REQUIRED");
        }
        if (!valid(existing)) {
            throw invalid("INVALID_FORMAT");
        }
        return existing;
    }

    public String renewedHeader(String value) {
        return ResponseCookie.from(properties.browserBindingCookieName(), value)
                .httpOnly(true).secure(properties.isCookieSecure()).sameSite("Lax")
                .path("/").maxAge(600).build().toString();
    }

    private String singleValue(HttpServletRequest request) {
        String name = properties.browserBindingCookieName();
        List<String> raw = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders("Cookie"))) {
            for (String pair : header.split(";", -1)) {
                int equals = pair.indexOf('=');
                String key = (equals < 0 ? pair : pair.substring(0, equals)).trim();
                if (name.equals(key)) {
                    raw.add(equals < 0 ? "" : pair.substring(equals + 1).trim());
                }
            }
        }
        List<String> parsed = new ArrayList<>();
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    parsed.add(cookie.getValue());
                }
            }
        }
        // 원문 헤더도 세어 컨테이너가 잘못된 값을 버려도 같은 이름의 중복을 거부합니다.
        if (raw.size() > 1 || parsed.size() > 1) {
            throw invalid("INVALID_FORMAT");
        }
        if (!raw.isEmpty()) {
            if (parsed.size() != 1 || !raw.getFirst().equals(parsed.getFirst())) {
                return "";
            }
            return raw.getFirst();
        }
        return parsed.isEmpty() ? null : parsed.getFirst();
    }

    private boolean valid(String value) {
        try {
            validator.validateBrowserBinding(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static LoginStartValidationException invalid(String code) {
        return new LoginStartValidationException("browserBinding", code);
    }
}
