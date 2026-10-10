package gguip1.community.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class OriginGuardFilterTest {
    private final SecurityProperties properties = configuredProperties();
    private final OriginGuardFilter filter = new OriginGuardFilter(properties);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("변경 요청에서 누락·중복·불일치 Origin을 같은 오류로 거부한다")
    void rejectsMissingDuplicateAndUnconfiguredOriginWithOneEnvelope() throws Exception {
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            for (String[] origins : new String[][]{null, {"null"}, {"https://app.example", "https://app.example"}, {"https://other.example"}}) {
                MockHttpServletRequest request = new MockHttpServletRequest(method, "/auth/logout");
                if (origins != null) for (String origin : origins) request.addHeader("Origin", origin);
                MockHttpServletResponse response = new MockHttpServletResponse();
                AtomicBoolean continued = new AtomicBoolean();

                filter.doFilter(request, response, (servletRequest, servletResponse) -> continued.set(true));

                assertThat(response.getStatus()).isEqualTo(403);
                assertThat(continued).isFalse();
                assertThat(json.readTree(response.getContentAsString()).at("/error/code").asText())
                        .isEqualTo("CSRF_INVALID");
            }
        }
    }

    @Test
    @DisplayName("고정 허용 Origin은 변경 요청을 통과시킨다")
    void allowsOnlyTheConfiguredOrigin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/users/me/nickname");
        request.addHeader("Origin", "https://app.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> continued.set(true));

        assertThat(continued).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Origin 없는 안전 메서드는 정상 응답으로 필터 chain을 통과한다")
    void allowsSafeMethodsWithoutOrigin() throws Exception {
        for (String method : new String[]{"GET", "HEAD", "OPTIONS"}) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/topics");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean continued = new AtomicBoolean();

            filter.doFilter(request, response, (servletRequest, servletResponse) -> continued.set(true));

            assertThat(continued).as("%s reaches the downstream chain", method).isTrue();
            assertThat(response.getStatus()).as("%s keeps the normal status", method).isEqualTo(200);
        }
    }

    private static SecurityProperties configuredProperties() {
        SecurityProperties properties = new SecurityProperties();
        properties.setAllowedOrigin("https://app.example");
        return properties;
    }
}
