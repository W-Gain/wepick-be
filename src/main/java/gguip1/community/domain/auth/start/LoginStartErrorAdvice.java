package gguip1.community.domain.auth.start;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 새 로그인 경로만 공통 오류 형태로 처리하고, 기존 API 응답은 바꾸지 않습니다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = KakaoLoginStartController.class)
public class LoginStartErrorAdvice {
    public record Envelope(Error error) { }
    public record Error(String code, String message, Map<String, ?> details) { }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handle(Exception failure, HttpServletRequest request) {
        int status = 500;
        Error error = new Error("INTERNAL_ERROR", "로그인을 시작하지 못했어요. 잠시 후 다시 확인해 주세요.", Map.of());
        if (failure instanceof LoginStartValidationException invalid) {
            status = 400;
            error = new Error("VALIDATION_FAILED", "로그인 요청 정보를 확인할 수 없어요.", Map.of("fields", invalid.fields()));
        } else if (failure instanceof KakaoLoginStartController.ActiveAttemptException) {
            status = 409;
            error = new Error("ATTEMPT_ALREADY_ACTIVE", "이미 진행 중인 로그인 시도가 있어요. 원래 탭을 확인해 주세요.", Map.of());
        }
        ResponseEntity.BodyBuilder response = KakaoLoginStartController.headers(ResponseEntity.status(status));
        if (prefersHtml(request.getHeader("Accept"))) {
            // 안내는 정적인 공개 문구만 쓰고 입력값·예외·설정값을 HTML에 넣지 않습니다.
            String html = "<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>로그인 안내 · WePick</title><body><main><h1>로그인 안내</h1><p>"
                    + error.message() + "</p><a href=\"/\">홈으로 돌아가기</a></main></body></html>";
            return response.contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                    .header("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
                    .body(html);
        }
        return response.contentType(MediaType.APPLICATION_JSON).body(new Envelope(error));
    }

    static boolean prefersHtml(String accept) {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        try {
            List<MediaType> types = MediaType.parseMediaTypes(accept);
            // 명시적인 q=0은 와일드카드보다 우선하며, 품질이 같으면 JSON을 반환합니다.
            boolean explicitHtml = types.stream().anyMatch(type -> type.getType().equals("text") && type.getSubtype().equals("html"));
            return explicitHtml && quality(types, MediaType.TEXT_HTML) > quality(types, MediaType.APPLICATION_JSON);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static double quality(List<MediaType> types, MediaType target) {
        int specificity = -1;
        double quality = 0;
        for (MediaType type : types) {
            if (!type.includes(target)) {
                continue;
            }
            int current = type.isWildcardType() ? 0 : type.isWildcardSubtype() ? 1 : 2;
            if (current > specificity) {
                specificity = current;
                quality = type.getQualityValue();
            } else if (current == specificity) {
                quality = Math.max(quality, type.getQualityValue());
            }
        }
        return quality;
    }
}
