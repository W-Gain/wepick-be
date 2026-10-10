package gguip1.community.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.util.Objects;

/** 허용 Origin을 Host나 Forwarded 요청 헤더가 아닌 고정 설정에서 가져옵니다. */
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {
    private String allowedOrigin = "http://127.0.0.1:5173";

    public String getAllowedOrigin() {
        return allowedOrigin;
    }

    public void setAllowedOrigin(String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    public String validatedAllowedOrigin() {
        if (allowedOrigin == null || allowedOrigin.isBlank() || !allowedOrigin.equals(allowedOrigin.trim())) {
            throw new IllegalStateException("app.security.allowed-origin is required");
        }
        URI uri;
        try {
            uri = URI.create(allowedOrigin);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalStateException("app.security.allowed-origin is invalid");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
            throw new IllegalStateException("app.security.allowed-origin is invalid");
        }
        return Objects.requireNonNull(uri.toString());
    }

    @PostConstruct
    void validateOnStartup() {
        validatedAllowedOrigin();
    }
}
