package gguip1.community.global.config;

import gguip1.community.global.security.OriginGuardFilter;
import gguip1.community.global.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Origin을 CORS·Spring Security 전에 검증해 변경 요청 오류 봉투를 고정합니다. */
@Configuration
public class OriginGuardConfiguration {
    @Bean
    FilterRegistrationBean<OriginGuardFilter> originGuardFilterRegistration(SecurityProperties properties) {
        FilterRegistrationBean<OriginGuardFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new OriginGuardFilter(properties));
        // Spring Security's servlet proxy defaults to -100; this guard must run before it.
        registration.setOrder(-200);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
