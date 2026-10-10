package gguip1.community.global.response;

import java.util.Map;

/** 인증·Origin·CSRF 실패를 목표 공통 error.code 봉투로 표현합니다. */
public record ApiErrorEnvelope(Error error) {
    public static ApiErrorEnvelope of(String code, String message) {
        return new ApiErrorEnvelope(new Error(code, message, Map.of()));
    }

    public record Error(String code, String message, Map<String, Object> details) {
    }
}
