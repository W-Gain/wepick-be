package gguip1.community.domain.topic.admin;

import gguip1.community.global.response.ApiErrorEnvelope;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/** 관리자 Draft API만 목표 오류 봉투와 필드 코드를 사용하도록 제한합니다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AdminTopicDraftController.class)
public class AdminTopicDraftExceptionHandler {
    @ExceptionHandler(AdminTopicDraftException.class)
    public ResponseEntity<ApiErrorEnvelope> handleDraftError(AdminTopicDraftException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiErrorEnvelope(new ApiErrorEnvelope.Error(
                exception.code(), exception.getMessage(), exception.details())));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorEnvelope> handleMalformedRequest(Exception exception) {
        var details = Map.<String, Object>of("fields", List.of(Map.of("field", "body", "code", "INVALID_FORMAT")));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiErrorEnvelope(
                new ApiErrorEnvelope.Error("VALIDATION_FAILED", "요청 값이 올바르지 않습니다.", details)));
    }
}
