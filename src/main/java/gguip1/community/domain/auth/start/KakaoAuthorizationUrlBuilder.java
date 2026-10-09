package gguip1.community.domain.auth.start;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.regex.Pattern;

/** 고정된 Kakao 인가 endpoint에 필요한 네 값만 query encoding해 붙입니다. */
@Component
public class KakaoAuthorizationUrlBuilder {
    private static final String AUTHORIZATION_ENDPOINT = "https://kauth.kakao.com/oauth/authorize";
    private static final Pattern STATE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final KakaoAuthorizationProperties properties;

    public KakaoAuthorizationUrlBuilder(KakaoAuthorizationProperties properties) {
        this.properties = properties;
    }

    /** 성공적으로 저장된 32-byte state만 인가 URL에 넣고 client secret이나 returnTo는 전달하지 않습니다. */
    public URI build(String state) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Kakao authorization is disabled");
        }
        if (state == null || !STATE_PATTERN.matcher(state).matches()) {
            throw new IllegalArgumentException("Invalid state");
        }

        String query = "response_type=code"
                + "&client_id=" + encodeQueryValue(properties.getClientId())
                + "&redirect_uri=" + encodeQueryValue(properties.validatedRedirectUri().toASCIIString())
                + "&state=" + encodeQueryValue(state);
        return URI.create(AUTHORIZATION_ENDPOINT + "?" + query);
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
