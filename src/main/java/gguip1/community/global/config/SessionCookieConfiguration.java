package gguip1.community.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** Spring Session과 Servlet 설정이 같은 세션 쿠키 이름·보안 속성을 사용하게 합니다. */
@Configuration
public class SessionCookieConfiguration {
    @Bean
    CookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String cookieName,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure,
            @Value("${server.servlet.session.cookie.same-site:Lax}") String sameSite) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(cookieName);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite(sameSite);
        return serializer;
    }
}
