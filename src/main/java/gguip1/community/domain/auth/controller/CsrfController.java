package gguip1.community.domain.auth.controller;

import gguip1.community.global.response.ApiDataResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** FE가 변경 요청 header에 넣을 세션 결속 CSRF token을 반환합니다. */
@RestController
public class CsrfController {
    public record CsrfResponse(String token) { }

    @GetMapping("/csrf")
    public ResponseEntity<ApiDataResponse<CsrfResponse>> token(@RequestAttribute("_csrf") CsrfToken csrfToken) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new ApiDataResponse<>(new CsrfResponse(csrfToken.getToken())));
    }
}
