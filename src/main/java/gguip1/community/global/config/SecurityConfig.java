package gguip1.community.global.config;

import gguip1.community.global.security.ApiAccessDeniedHandler;
import gguip1.community.global.security.ApiAuthenticationEntryPoint;
import gguip1.community.global.security.CurrentActorFilter;
import gguip1.community.global.security.HeaderOnlyXorCsrfTokenRequestHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import java.util.List;

/** 단일 Spring Security 인증 원본과 모든 변경 요청 보호를 구성합니다. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityContextRepository securityContextRepository() {
        HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();
        repository.setDisableUrlRewriting(true);
        return repository;
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        SessionFixationProtectionStrategy freshSession = new SessionFixationProtectionStrategy();
        freshSession.setMigrateSessionAttributes(false);
        return new CompositeSessionAuthenticationStrategy(List.of(
                new CsrfAuthenticationStrategy(csrfTokenRepository),
                freshSession));
    }

    @Bean
    ApiAuthenticationEntryPoint apiAuthenticationEntryPoint() {
        return new ApiAuthenticationEntryPoint();
    }

    @Bean
    ApiAccessDeniedHandler apiAccessDeniedHandler() {
        return new ApiAccessDeniedHandler();
    }

    @Bean
    @Order(0)
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            SecurityContextRepository contextRepository,
            CsrfTokenRepository csrfTokenRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            gguip1.community.domain.user.repository.UserRepository users,
            ApiAuthenticationEntryPoint entryPoint,
            ApiAccessDeniedHandler deniedHandler) throws Exception {
        CurrentActorFilter activeMember = new CurrentActorFilter(users, contextRepository);

        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new HeaderOnlyXorCsrfTokenRequestHandler()))
                .securityContext(context -> context
                        .securityContextRepository(contextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session
                        .sessionAuthenticationStrategy(sessionAuthenticationStrategy))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/me", "/users/me").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/users/me", "/users/me/**").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/users/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/topics").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/topics/*").hasRole("ADMIN")
                        .requestMatchers("/admin/topics", "/admin/topics/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/images/profile", "/images/posts", "/posts",
                                "/posts/*/like", "/posts/*/comments", "/topics/*/vote").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/posts/*", "/posts/*/comments/*", "/topics/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/posts/*", "/posts/*/like", "/posts/*/comments/*").authenticated()
                        .anyRequest().permitAll())
                .addFilterAfter(activeMember, CsrfFilter.class)
                .build();
    }
}
