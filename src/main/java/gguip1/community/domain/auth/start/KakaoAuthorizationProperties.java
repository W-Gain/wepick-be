package gguip1.community.domain.auth.start;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Kakao 시작 설정은 기본 비활성화하고, 켤 때 고정 callback 주소만 검증합니다. */
@Component
@ConfigurationProperties(prefix = "app.auth.kakao")
public class KakaoAuthorizationProperties implements org.springframework.beans.factory.InitializingBean {
    private boolean enabled;
    private String clientId;
    private String redirectUri;
    private boolean cookieSecure = true;
    private boolean allowInsecureLoopbackCookie;
    private URI validatedRedirectUri;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public boolean isAllowInsecureLoopbackCookie() {
        return allowInsecureLoopbackCookie;
    }

    public void setAllowInsecureLoopbackCookie(boolean allowInsecureLoopbackCookie) {
        this.allowInsecureLoopbackCookie = allowInsecureLoopbackCookie;
    }

    String browserBindingCookieName() {
        return cookieSecure ? "__Host-wepick-login-binding" : "wepick-login-binding-local";
    }

    URI validatedRedirectUri() {
        if (!enabled || validatedRedirectUri == null) {
            throw new IllegalStateException("Kakao authorization is disabled or incomplete");
        }
        return validatedRedirectUri;
    }

    @Override
    public void afterPropertiesSet() {
        validatedRedirectUri = null;
        if (!enabled) {
            return;
        }
        if (clientId == null || clientId.isBlank() || !clientId.equals(clientId.trim())) {
            throw new IllegalStateException(
                    "app.auth.kakao.client-id is required when Kakao authorization is enabled");
        }
        if (redirectUri == null || redirectUri.isBlank() || !redirectUri.equals(redirectUri.trim())) {
            throw new IllegalStateException(
                    "app.auth.kakao.redirect-uri is required when Kakao authorization is enabled");
        }
        validatedRedirectUri = validateRedirectUri(redirectUri);
        boolean loopbackHttp = "http".equalsIgnoreCase(validatedRedirectUri.getScheme())
                && isLoopbackHost(validatedRedirectUri.getHost());
        if (loopbackHttp && (cookieSecure || !allowInsecureLoopbackCookie)) {
            throw invalidCookieSecurity();
        }
        if (!loopbackHttp && !cookieSecure) {
            throw invalidCookieSecurity();
        }
    }

    private static URI validateRedirectUri(String configuredUri) {
        URI uri;
        try {
            uri = new URI(configuredUri);
        } catch (URISyntaxException ignored) {
            throw invalidRedirectUri();
        }

        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw invalidRedirectUri();
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return uri;
        }
        if ("http".equalsIgnoreCase(scheme) && isLoopbackHost(uri.getHost())) {
            return uri;
        }
        throw invalidRedirectUri();
    }

    private static boolean isLoopbackHost(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }
        return normalizedHost.equals("localhost")
                || normalizedHost.equals("127.0.0.1")
                || normalizedHost.equals("::1");
    }

    private static IllegalStateException invalidRedirectUri() {
        // 예외 원인이나 설정값을 연결하지 않아 시작 로그에 callback 원문을 남기지 않습니다.
        return new IllegalStateException(
                "app.auth.kakao.redirect-uri must be an absolute HTTPS URI or an HTTP loopback URI");
    }

    private static IllegalStateException invalidCookieSecurity() {
        return new IllegalStateException(
                "HTTP loopback Kakao authorization requires explicit insecure-cookie opt-in; other callbacks require Secure cookies");
    }
}
