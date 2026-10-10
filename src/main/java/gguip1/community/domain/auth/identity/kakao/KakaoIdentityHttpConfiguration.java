package gguip1.community.domain.auth.identity.kakao;

import gguip1.community.domain.auth.identity.KakaoIdentityClient;
import gguip1.community.domain.auth.start.KakaoAuthorizationProperties;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** 코드 교환 HTTP 호출은 유한 시간 제한, redirect 및 자동 재전송 없이 구성합니다. */
@Configuration(proxyBeanMethods = false)
public class KakaoIdentityHttpConfiguration {
    @Bean(destroyMethod = "close")
    CloseableHttpClient kakaoIdentityHttpClient() {
        return HttpClients.custom()
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .build();
    }

    @Bean
    HttpComponentsClientHttpRequestFactory kakaoIdentityRequestFactory(CloseableHttpClient kakaoIdentityHttpClient) {
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(kakaoIdentityHttpClient);
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setConnectionRequestTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return requestFactory;
    }

    @Bean
    RestClient kakaoIdentityRestClient(HttpComponentsClientHttpRequestFactory kakaoIdentityRequestFactory) {
        return RestClient.builder()
                .requestFactory(kakaoIdentityRequestFactory)
                .defaultStatusHandler(
                        status -> status.value() != HttpStatus.OK.value(),
                        (request, response) -> {
                            throw new gguip1.community.domain.auth.identity.KakaoIdentityLookupException();
                        })
                .messageConverters(converters -> {
                    // OAuth token JSON 전용 converter와 code 교환 폼 본문 converter를 명시합니다.
                    converters.add(0, new OAuth2AccessTokenResponseHttpMessageConverter());
                    converters.add(1, new FormHttpMessageConverter());
                })
                .build();
    }

    @Bean
    RestClientAuthorizationCodeTokenResponseClient kakaoTokenResponseClient(RestClient kakaoIdentityRestClient) {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(kakaoIdentityRestClient);
        return client;
    }

    @Bean
    KakaoIdentityClient kakaoIdentityClient(
            KakaoAuthorizationProperties properties,
            RestClientAuthorizationCodeTokenResponseClient kakaoTokenResponseClient,
            RestClient kakaoIdentityRestClient) {
        return new DefaultKakaoIdentityClient(properties, kakaoTokenResponseClient, kakaoIdentityRestClient);
    }
}
