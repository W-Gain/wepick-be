package gguip1.community.domain.auth.callback;

import gguip1.community.domain.auth.attempt.LoginAttempt;
import gguip1.community.domain.auth.attempt.LoginAttemptInputValidator;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 소비된 시도에 저장된 값만 사용해 같은 출처의 FE 완료 경로를 만듭니다. */
@Component
public class KakaoLoginCompletionRedirectBuilder {
    public enum Result {
        SUCCESS,
        CANCELLED,
        FAILED
    }

    private final LoginAttemptInputValidator inputValidator;

    public KakaoLoginCompletionRedirectBuilder(LoginAttemptInputValidator inputValidator) {
        this.inputValidator = inputValidator;
    }

    /** 성공 호출자는 회원·병합 commit과 JDBC 세션 저장 완료 뒤에만 이 메서드를 호출해야 합니다. */
    public String withConsumedAttempt(LoginAttempt attempt, Result result, boolean keptMemberVote) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(result, "result");
        if (keptMemberVote && result != Result.SUCCESS) {
            throw new IllegalArgumentException("Merge outcome only applies to successful login");
        }

        String clientAttemptId = inputValidator.validateClientAttemptId(attempt.clientAttemptId());
        String returnTo = inputValidator.normalizeReturnTo(attempt.returnTo());
        UriComponentsBuilder location = UriComponentsBuilder.fromPath("/auth/complete")
                .queryParam("attempt", "{attempt}")
                .queryParam("returnTo", "{returnTo}")
                .queryParam("result", "{result}");
        if (keptMemberVote) {
            location.queryParam("merge", "{merge}");
        }
        // 변수 확장 전에 템플릿을 인코딩해 returnTo의 '+'도 query 구분자로 해석되지 않게 합니다.
        var variables = keptMemberVote
                ? Map.of("attempt", clientAttemptId, "returnTo", returnTo,
                        "result", result.name().toLowerCase(Locale.ROOT), "merge", "kept_member_vote")
                : Map.of("attempt", clientAttemptId, "returnTo", returnTo,
                        "result", result.name().toLowerCase(Locale.ROOT));
        return location.encode().buildAndExpand(variables).toUriString();
    }

    /** state/binding을 확인하지 못한 callback은 요청값을 전혀 반영하지 않습니다. */
    public String withoutVerifiedAttempt() {
        return "/auth/complete?result=failed";
    }
}
