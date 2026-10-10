package gguip1.community.domain.auth.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 운영과 HTTP 로컬 익명 식별 쿠키를 서로 다른 이름으로 분리합니다. */
@Component
@ConfigurationProperties(prefix = "app.auth.anonymous-voter")
public class AnonymousVoterCookieProperties {
    private boolean secure = true;

    public boolean isSecure() { return secure; }
    public void setSecure(boolean secure) { this.secure = secure; }

    public String cookieName() { return AnonymousVoterCookie.name(secure); }
}
