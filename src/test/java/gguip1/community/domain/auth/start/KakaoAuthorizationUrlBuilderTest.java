package gguip1.community.domain.auth.start;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class KakaoAuthorizationUrlBuilderTest {
    private static final String STATE = "A".repeat(43);

    @Test
    @DisplayName("고정 Kakao endpoint에 인가에 필요한 네 query 값만 인코딩해 넣는다")
    void buildsEncodedAuthorizationUrlWithOnlyExpectedParameters() throws Exception {
        KakaoAuthorizationProperties properties = enabledProperties(
                "rest+client/id", "https://login.wepick.example/auth/callback?fixed=a+b&part=%26");
        URI authorizationUri = new KakaoAuthorizationUrlBuilder(properties).build(STATE);
        Map<String, String> query = decodeQuery(authorizationUri);

        assertThat(authorizationUri.getScheme()).isEqualTo("https");
        assertThat(authorizationUri.getHost()).isEqualTo("kauth.kakao.com");
        assertThat(authorizationUri.getPath()).isEqualTo("/oauth/authorize");
        assertThat(query.keySet()).isEqualTo(Set.of("response_type", "client_id", "redirect_uri", "state"));
        assertThat(query.get("response_type")).isEqualTo("code");
        assertThat(query.get("client_id")).isEqualTo("rest+client/id");
        assertThat(query.get("redirect_uri"))
                .isEqualTo("https://login.wepick.example/auth/callback?fixed=a+b&part=%26");
        assertThat(query.get("state")).isEqualTo(STATE);
        assertThat(authorizationUri.getRawQuery()).contains("redirect_uri=https%3A%2F%2F")
                .doesNotContain("client_secret", "scope", "prompt", "returnTo");
    }

    @Test
    @DisplayName("설정이 비활성화되면 인가 URL을 만들지 않는다")
    void disabledAuthorizationFailsClosed() {
        KakaoAuthorizationProperties properties = new KakaoAuthorizationProperties();
        assertThatIllegalStateException()
                .isThrownBy(() -> new KakaoAuthorizationUrlBuilder(properties).build(STATE));
    }

    @Test
    @DisplayName("저장된 state 형식이 아니면 인가 URL을 만들지 않는다")
    void rejectsMissingOrMalformedState() throws Exception {
        KakaoAuthorizationUrlBuilder builder = new KakaoAuthorizationUrlBuilder(
                enabledProperties("client-id", "https://login.wepick.example/auth/callback"));

        assertThatIllegalArgumentException().isThrownBy(() -> builder.build(null));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.build("state&prompt=none"));
    }

    private static KakaoAuthorizationProperties enabledProperties(String clientId, String redirectUri)
            throws Exception {
        KakaoAuthorizationProperties properties = new KakaoAuthorizationProperties();
        properties.setEnabled(true);
        properties.setClientId(clientId);
        properties.setRedirectUri(redirectUri);
        properties.afterPropertiesSet();
        return properties;
    }

    private static Map<String, String> decodeQuery(URI uri) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String parameter : uri.getRawQuery().split("&")) {
            String[] nameAndValue = parameter.split("=", 2);
            query.put(URLDecoder.decode(nameAndValue[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(nameAndValue[1], StandardCharsets.UTF_8));
        }
        return query;
    }
}
