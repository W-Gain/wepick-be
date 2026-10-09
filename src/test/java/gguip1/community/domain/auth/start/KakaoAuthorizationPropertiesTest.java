package gguip1.community.domain.auth.start;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoAuthorizationPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesTestConfiguration.class);

    @Test
    @DisplayName("Kakao 설정이 없어도 기본 비활성 상태로 설정 context가 열린다")
    void missingKakaoSettingsLeaveAuthorizationDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KakaoAuthorizationProperties.class).isEnabled()).isFalse();
        });
    }

    @Test
    @DisplayName("Kakao 시작을 켰지만 client ID가 없으면 값 없이 fail-fast한다")
    void enabledAuthorizationRequiresClientIdWithoutLoggingValues() {
        contextRunner
                .withPropertyValues(
                        "app.auth.kakao.enabled=true",
                        "app.auth.kakao.redirect-uri=https://login.wepick.example/auth/callback?secret=do-not-log")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("app.auth.kakao.client-id")
                            .doesNotContain("do-not-log");
                });
    }

    @Test
    @DisplayName("Kakao 시작을 켰지만 callback 주소가 없으면 fail-fast한다")
    void enabledAuthorizationRequiresCallbackRedirectUri() {
        contextRunner
                .withPropertyValues(
                        "app.auth.kakao.enabled=true",
                        "app.auth.kakao.client-id=public-client-id")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("app.auth.kakao.redirect-uri");
                });
    }

    @Test
    @DisplayName("HTTPS callback과 loopback HTTP callback만 허용한다")
    void validatesCallbackSchemeAndLoopbackHttp() {
        contextRunner
                .withPropertyValues(
                        "app.auth.kakao.enabled=true",
                        "app.auth.kakao.client-id=public-client-id",
                        "app.auth.kakao.cookie-secure=false",
                        "app.auth.kakao.allow-insecure-loopback-cookie=true",
                        "app.auth.kakao.redirect-uri=http://localhost:8080/auth/kakao/callback")
                .run(context -> assertThat(context).hasNotFailed());

        contextRunner
                .withPropertyValues(
                        "app.auth.kakao.enabled=true",
                        "app.auth.kakao.client-id=public-client-id",
                        "app.auth.kakao.redirect-uri=http://login.wepick.example/auth/kakao/callback")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("must be an absolute HTTPS URI or an HTTP loopback URI");
                });
    }

    @Test
    @DisplayName("HTTP loopback은 명시적으로 허용해야 하고 HTTPS의 Secure 쿠키를 끌 수 없다")
    void insecureCookiesRequireExplicitLoopbackOptIn() {
        contextRunner.withPropertyValues(
                        "app.auth.kakao.enabled=true", "app.auth.kakao.client-id=public-client-id",
                        "app.auth.kakao.redirect-uri=http://localhost:5173/api/auth/kakao/callback")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "app.auth.kakao.enabled=true", "app.auth.kakao.client-id=public-client-id",
                        "app.auth.kakao.redirect-uri=https://wepick.example/api/auth/kakao/callback",
                        "app.auth.kakao.cookie-secure=false", "app.auth.kakao.allow-insecure-loopback-cookie=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(KakaoAuthorizationProperties.class)
    static class PropertiesTestConfiguration {
    }

    @Test @DisplayName("공백 설정과 userinfo·fragment·상대·opaque callback은 값 없는 설정 오류로 거부한다")
    void rejectsAmbiguousConfigurationWithoutEchoingValues() {
        for (String redirect : new String[]{" ", " https://wepick.example/callback", "https://user:secret@wepick.example/callback",
                "https://wepick.example/callback#secret", "/callback", "mailto:secret@example.com", "https://bad host/callback"}) {
            var properties = configured(redirect);
            assertThatThrownBy(properties::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.auth.kakao.redirect-uri").hasMessageNotContaining("secret");
        }
        for (String client : new String[]{" ", " public-client"}) {
            var properties = configured("https://wepick.example/callback");
            properties.setClientId(client);
            assertThatThrownBy(properties::afterPropertiesSet).hasMessageContaining("app.auth.kakao.client-id");
        }
        assertThatThrownBy(new KakaoAuthorizationProperties()::validatedRedirectUri)
                .hasMessageContaining("disabled or incomplete");
    }

    @Test @DisplayName("IPv6 loopback도 명시적 비보안 쿠키 허용이 필요하고 허용 없이 끄면 설정 오류다")
    void ipv6LoopbackAndMissingOptInFollowCookiePolicy() {
        var ipv6 = configured("http://[::1]:5173/callback");
        ipv6.setCookieSecure(false); ipv6.setAllowInsecureLoopbackCookie(true); ipv6.afterPropertiesSet();
        assertThat(ipv6.validatedRedirectUri().getHost()).isEqualTo("[::1]");
        var missing = configured("http://127.0.0.1:5173/callback");
        missing.setCookieSecure(false);
        assertThatThrownBy(missing::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit insecure-cookie opt-in");
    }

    private static KakaoAuthorizationProperties configured(String redirect) {
        var properties = new KakaoAuthorizationProperties();
        properties.setEnabled(true); properties.setClientId("public-client"); properties.setRedirectUri(redirect);
        return properties;
    }
}
