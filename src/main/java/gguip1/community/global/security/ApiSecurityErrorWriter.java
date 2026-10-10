package gguip1.community.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import gguip1.community.global.response.ApiErrorEnvelope;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 필터 단계의 인증·CSRF 오류를 기존 공통 JSON 봉투로 씁니다. */
final class ApiSecurityErrorWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ApiSecurityErrorWriter() {
    }

    static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        JSON.writeValue(response.getOutputStream(), ApiErrorEnvelope.of(code, message));
    }
}
