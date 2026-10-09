package gguip1.community.domain.auth.start;

import gguip1.community.domain.auth.attempt.LoginAttemptCreationResult;
import gguip1.community.domain.auth.attempt.LoginAttemptInputValidator;
import gguip1.community.domain.auth.attempt.LoginAttemptStartResult;
import gguip1.community.domain.auth.attempt.LoginAttemptStarter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 준비 쿠키와 검증·저장 순서를 묶습니다. 회원 로그인과 callback은 별도 책임입니다. */
@RestController
@RequestMapping("/auth/kakao")
public class KakaoLoginStartController {
    private final KakaoAuthorizationProperties properties;
    private final KakaoAuthorizationUrlBuilder urls;
    private final LoginBrowserBindingCookie cookies;
    private final LoginAttemptInputValidator validator;
    private final LoginAttemptStarter starter;

    public KakaoLoginStartController(KakaoAuthorizationProperties properties,
                                    KakaoAuthorizationUrlBuilder urls, LoginBrowserBindingCookie cookies,
                                    LoginAttemptInputValidator validator, LoginAttemptStarter starter) {
        this.properties = properties;
        this.urls = urls;
        this.cookies = cookies;
        this.validator = validator;
        this.starter = starter;
    }

    @Operation(summary = "로그인 브라우저 확인 쿠키 준비", description = "FE는 탭 사이에서 준비 응답 완료까지 직렬화합니다. 로그인 시도나 인증 세션을 만들지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "확인 쿠키 준비 완료", headers = @Header(name = "Set-Cookie", schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "400", description = "중복 확인 쿠키", content = {@Content(mediaType = "application/json", schema = @Schema(implementation = LoginStartErrorAdvice.Envelope.class)), @Content(mediaType = "text/html", schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "500", description = "설정 또는 서버 오류", content = {@Content(mediaType = "application/json", schema = @Schema(implementation = LoginStartErrorAdvice.Envelope.class)), @Content(mediaType = "text/html", schema = @Schema(type = "string"))})
    })
    @GetMapping("/prepare")
    public ResponseEntity<Void> prepare(HttpServletRequest request) {
        properties.validatedRedirectUri();
        String binding = cookies.prepare(request);
        return headers(ResponseEntity.status(204))
                .header(HttpHeaders.SET_COOKIE, cookies.renewedHeader(binding)).build();
    }

    @Operation(summary = "카카오 로그인 시작", description = "준비된 확인 쿠키가 필요하며 브라우저 페이지 이동으로 호출합니다. 저장된 시도의 state만 카카오 인가 주소로 전달합니다.")
    @Parameters({
            @Parameter(name = "attempt", in = ParameterIn.QUERY, required = true, schema = @Schema(type = "string", minLength = 22, maxLength = 64, pattern = "[A-Za-z0-9_-]+")),
            @Parameter(name = "returnTo", in = ParameterIn.QUERY, required = true, schema = @Schema(type = "string", maxLength = 512)),
            @Parameter(name = "__Host-wepick-login-binding", in = ParameterIn.COOKIE, required = true, schema = @Schema(type = "string"), description = "prepare가 발급한 HttpOnly 확인 쿠키. 명시적 loopback HTTP 환경은 별도 로컬 쿠키 이름을 사용합니다.")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "카카오 인가 페이지 이동", headers = @Header(name = "Location", schema = @Schema(type = "string", format = "uri"))),
            @ApiResponse(responseCode = "400", description = "입력 또는 확인 쿠키 오류", content = {@Content(mediaType = "application/json", schema = @Schema(implementation = LoginStartErrorAdvice.Envelope.class)), @Content(mediaType = "text/html", schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "409", description = "활성 시도 ID 중복", content = {@Content(mediaType = "application/json", schema = @Schema(implementation = LoginStartErrorAdvice.Envelope.class)), @Content(mediaType = "text/html", schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "500", description = "설정 또는 서버 오류", content = {@Content(mediaType = "application/json", schema = @Schema(implementation = LoginStartErrorAdvice.Envelope.class)), @Content(mediaType = "text/html", schema = @Schema(type = "string"))})
    })
    @GetMapping("/start")
    public ResponseEntity<Void> start(HttpServletRequest request) {
        String attempt = query(request, "attempt", 64);
        String returnTo = query(request, "returnTo", 512);
        validate("attempt", () -> validator.validateClientAttemptId(attempt));
        validate("returnTo", () -> validator.normalizeReturnTo(returnTo));
        String binding = cookies.requirePrepared(request);
        // 저장 뒤에 설정 오류를 발견하지 않도록 SQL 실행 전에 구성을 확인합니다.
        properties.validatedRedirectUri();
        LoginAttemptStartResult result = starter.start(binding, attempt, returnTo);
        if (result.status() == LoginAttemptCreationResult.ACTIVE_ATTEMPT_EXISTS) {
            throw new ActiveAttemptException();
        }
        return headers(ResponseEntity.status(302))
                .location(urls.build(result.state().orElseThrow()))
                .header(HttpHeaders.SET_COOKIE, cookies.renewedHeader(binding)).build();
    }

    static ResponseEntity.BodyBuilder headers(ResponseEntity.BodyBuilder response) {
        return response.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.VARY, "Accept")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff");
    }

    private static String query(HttpServletRequest request, String name, int maxLength) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length == 0 || values[0] == null || values[0].isEmpty()) {
            throw new LoginStartValidationException(name, "REQUIRED");
        }
        if (values.length != 1) {
            throw new LoginStartValidationException(name, "INVALID_FORMAT");
        }
        if (values[0].length() > maxLength) {
            throw new LoginStartValidationException(name, "TOO_LONG");
        }
        return values[0];
    }

    private static void validate(String field, Runnable check) {
        try {
            check.run();
        } catch (IllegalArgumentException ignored) {
            throw new LoginStartValidationException(field, "INVALID_FORMAT");
        }
    }

    static final class ActiveAttemptException extends RuntimeException {
        ActiveAttemptException() { super("Active login attempt exists"); }
    }
}
