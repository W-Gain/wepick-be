package gguip1.community.domain.topic.admin;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** 새 관리자 토픽 API의 오류 코드를 legacy ErrorCode 봉투와 분리합니다. */
public final class AdminTopicDraftException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    private AdminTopicDraftException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, Object> details() { return details; }

    public static AdminTopicDraftException validation(String field, String fieldCode) {
        return new AdminTopicDraftException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "요청 값이 올바르지 않습니다.", Map.of("fields", List.of(Map.of("field", field, "code", fieldCode))));
    }

    public static AdminTopicDraftException invalidCursor() {
        return new AdminTopicDraftException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR",
                "목록 cursor가 올바르지 않습니다.", Map.of());
    }

    public static AdminTopicDraftException notFound() {
        return new AdminTopicDraftException(HttpStatus.NOT_FOUND, "TOPIC_NOT_FOUND",
                "토픽을 찾을 수 없습니다.", Map.of());
    }

    public static AdminTopicDraftException conflict(String code, String message) {
        return new AdminTopicDraftException(HttpStatus.CONFLICT, code, message, Map.of());
    }

    public static AdminTopicDraftException duplicateTitle() {
        return conflict("DUPLICATE_TOPIC", "같은 제목의 토픽이 이미 있습니다.");
    }
}
