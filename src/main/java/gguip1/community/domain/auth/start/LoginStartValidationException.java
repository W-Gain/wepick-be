package gguip1.community.domain.auth.start;

import java.util.List;

/** 오류에는 필드 이름과 공개 코드만 보관하고 요청 원문은 남기지 않습니다. */
final class LoginStartValidationException extends RuntimeException {
    private final List<Field> fields;

    LoginStartValidationException(String field, String code) {
        super("Invalid login start request");
        fields = List.of(new Field(field, code));
    }

    List<Field> fields() {
        return fields;
    }

    public record Field(String field, String code) { }
}
