package gguip1.community.domain.auth.identity.kakao;

import com.fasterxml.jackson.databind.JsonNode;
import gguip1.community.domain.auth.identity.KakaoIdentity;
import gguip1.community.domain.auth.identity.KakaoIdentityClient;
import gguip1.community.domain.auth.identity.KakaoIdentityLookupException;
import gguip1.community.domain.auth.start.KakaoAuthorizationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.regex.Pattern;

/** Kakao authorization-code 교환과 ID 전용 사용자 조회를 DB 트랜잭션 밖에서 수행합니다. */
public final class DefaultKakaoIdentityClient implements KakaoIdentityClient {
    static final URI AUTHORIZATION_ENDPOINT = URI.create("https://kauth.kakao.com/oauth/authorize");
    static final URI TOKEN_ENDPOINT = URI.create("https://kauth.kakao.com/oauth/token");
    static final URI USER_INFO_ENDPOINT = URI.create("https://kapi.kakao.com/v2/user/me");
    private static final Pattern STATE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final KakaoAuthorizationProperties properties;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient;
    private final RestClient restClient;
    private final URI tokenEndpoint;
    private final URI userInfoEndpoint;

    public DefaultKakaoIdentityClient(
            KakaoAuthorizationProperties properties,
            RestClientAuthorizationCodeTokenResponseClient tokenResponseClient,
            RestClient restClient) {
        this(properties, tokenResponseClient, restClient, TOKEN_ENDPOINT, USER_INFO_ENDPOINT);
    }

    DefaultKakaoIdentityClient(
            KakaoAuthorizationProperties properties,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient,
            RestClient restClient,
            URI tokenEndpoint,
            URI userInfoEndpoint) {
        this.properties = properties;
        this.tokenResponseClient = tokenResponseClient;
        this.restClient = restClient;
        this.tokenEndpoint = tokenEndpoint;
        this.userInfoEndpoint = userInfoEndpoint;
    }

    @Override
    public KakaoIdentity exchangeCodeAndLoadIdentity(String authorizationCode, String consumedState) {
        try {
            validateInput(authorizationCode, consumedState);
            ClientRegistration registration = clientRegistration();
            OAuth2AuthorizationCodeGrantRequest grantRequest = grantRequest(registration, authorizationCode, consumedState);
            OAuth2AccessToken accessToken = tokenResponseClient.getTokenResponse(grantRequest).getAccessToken();
            if (accessToken == null || accessToken.getTokenValue() == null || accessToken.getTokenValue().isBlank()) {
                throw new KakaoIdentityLookupException();
            }
            return loadIdentity(accessToken);
        } catch (KakaoIdentityLookupException exception) {
            throw exception;
        } catch (RuntimeException ignored) {
            // OAuth 오류와 HTTP 예외의 원인에는 code, token, secret 또는 응답 본문이 들어갈 수 있습니다.
            throw new KakaoIdentityLookupException();
        }
    }

    private void validateInput(String authorizationCode, String consumedState) {
        if (!properties.isEnabled()
                || authorizationCode == null
                || authorizationCode.isBlank()
                || !authorizationCode.equals(authorizationCode.trim())
                || authorizationCode.length() > 2048
                || consumedState == null
                || !STATE_PATTERN.matcher(consumedState).matches()) {
            throw new KakaoIdentityLookupException();
        }
    }

    private ClientRegistration clientRegistration() {
        String clientSecret = properties.validatedClientSecret();
        String redirectUri = properties.validatedRedirectUri().toASCIIString();
        return ClientRegistration.withRegistrationId("kakao")
                .clientId(properties.getClientId())
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .authorizationUri(AUTHORIZATION_ENDPOINT.toASCIIString())
                .tokenUri(tokenEndpoint.toASCIIString())
                .userInfoUri(userInfoEndpoint.toASCIIString())
                .userNameAttributeName("id")
                .clientName("Kakao")
                .build();
    }

    private static OAuth2AuthorizationCodeGrantRequest grantRequest(
            ClientRegistration registration,
            String authorizationCode,
            String consumedState) {
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(registration.getRedirectUri())
                .state(consumedState)
                .build();
        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success(authorizationCode)
                .redirectUri(registration.getRedirectUri())
                .state(consumedState)
                .build();
        return new OAuth2AuthorizationCodeGrantRequest(
                registration,
                new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse));
    }

    private KakaoIdentity loadIdentity(OAuth2AccessToken accessToken) {
        URI requestUri = UriComponentsBuilder.fromUri(userInfoEndpoint)
                .queryParam("property_keys", "[\"id\"]")
                .build()
                .encode()
                .toUri();
        JsonNode response = restClient.get()
                .uri(requestUri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken.getTokenValue())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        JsonNode id = response == null ? null : response.get("id");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong()) {
            throw new KakaoIdentityLookupException();
        }
        long providerUserId = id.longValue();
        if (providerUserId <= 0) {
            throw new KakaoIdentityLookupException();
        }
        return new KakaoIdentity(providerUserId);
    }
}
