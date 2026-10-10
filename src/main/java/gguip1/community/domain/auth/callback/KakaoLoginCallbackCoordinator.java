package gguip1.community.domain.auth.callback;

import gguip1.community.domain.auth.attempt.LoginAttempt;
import gguip1.community.domain.auth.attempt.LoginAttemptConsumer;
import gguip1.community.domain.auth.attempt.LoginAttemptInputValidator;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

/** callback의 유일한 state/binding을 먼저 소비한 뒤 성공·취소·실패를 분류합니다. */
@Service
public class KakaoLoginCallbackCoordinator {
    private static final Pattern STATE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final int MAX_CODE_LENGTH = 2048;
    private static final int MAX_ERROR_LENGTH = 128;

    private final LoginAttemptConsumer attempts;
    private final LoginAttemptInputValidator inputValidator;

    public KakaoLoginCallbackCoordinator(
            LoginAttemptConsumer attempts,
            LoginAttemptInputValidator inputValidator) {
        this.attempts = attempts;
        this.inputValidator = inputValidator;
    }

    /**
     * state를 소비할 수 없으면 결과가 비어 있습니다. 파라미터 오류는 state 소비 뒤 FAILED가 되어
     * 같은 callback을 다시 재생해 외부 code 교환으로 이어갈 수 없습니다.
     */
    public Optional<KakaoLoginCallbackDecision> consume(
            String[] stateValues,
            String rawBrowserBinding,
            String[] codeValues,
            String[] errorValues) {
        if (!isSingleValidState(stateValues) || !isValidBinding(rawBrowserBinding)) {
            return Optional.empty();
        }

        String state = stateValues[0];
        Optional<LoginAttempt> consumedAttempt = attempts.consume(state, rawBrowserBinding);
        if (consumedAttempt.isEmpty()) {
            return Optional.empty();
        }

        CallbackData callback = classify(codeValues, errorValues);
        return Optional.of(new KakaoLoginCallbackDecision(
                consumedAttempt.orElseThrow(), callback.outcome(), callback.authorizationCode(), state));
    }

    private boolean isSingleValidState(String[] values) {
        return values != null && values.length == 1
                && values[0] != null && STATE_PATTERN.matcher(values[0]).matches();
    }

    private boolean isValidBinding(String value) {
        try {
            inputValidator.validateBrowserBinding(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static CallbackData classify(String[] codeValues, String[] errorValues) {
        boolean hasCodeParameter = codeValues != null;
        boolean hasErrorParameter = errorValues != null;
        if (hasCodeParameter == hasErrorParameter) {
            return CallbackData.failed();
        }

        if (hasCodeParameter && isSingleSafeValue(codeValues, MAX_CODE_LENGTH)) {
            return new CallbackData(KakaoLoginCallbackDecision.Outcome.AUTHORIZATION_CODE, codeValues[0]);
        }

        if (hasErrorParameter && isSingleSafeValue(errorValues, MAX_ERROR_LENGTH)) {
            KakaoLoginCallbackDecision.Outcome outcome = "access_denied".equals(errorValues[0])
                    ? KakaoLoginCallbackDecision.Outcome.CANCELLED
                    : KakaoLoginCallbackDecision.Outcome.FAILED;
            return new CallbackData(outcome, null);
        }

        return CallbackData.failed();
    }

    private static boolean isSingleSafeValue(String[] values, int maxLength) {
        if (values.length != 1 || values[0] == null || values[0].isBlank()
                || values[0].length() > maxLength || !values[0].equals(values[0].trim())) {
            return false;
        }
        for (int index = 0; index < values[0].length(); index++) {
            if (Character.isISOControl(values[0].charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private record CallbackData(KakaoLoginCallbackDecision.Outcome outcome, String authorizationCode) {
        private static CallbackData failed() {
            return new CallbackData(KakaoLoginCallbackDecision.Outcome.FAILED, null);
        }
    }
}
