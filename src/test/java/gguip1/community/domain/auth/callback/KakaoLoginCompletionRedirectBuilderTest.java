package gguip1.community.domain.auth.callback;

import gguip1.community.domain.auth.attempt.LoginAttempt;
import gguip1.community.domain.auth.attempt.LoginAttemptInputValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoLoginCompletionRedirectBuilderTest {
    private final KakaoLoginCompletionRedirectBuilder redirects =
            new KakaoLoginCompletionRedirectBuilder(new LoginAttemptInputValidator());

    @Test
    @DisplayName("완료 URL은 소비된 시도의 복귀 정보만 고정 경로의 query로 인코딩한다")
    void encodesConsumedAttemptAndReturnToIntoSameOriginCompletionPath() {
        String returnTo = "/profile?tab=my%20votes&from=login";
        String location = redirects.withConsumedAttempt(
                new LoginAttempt("A".repeat(22), returnTo),
                KakaoLoginCompletionRedirectBuilder.Result.SUCCESS,
                true);

        URI uri = URI.create(location);
        Map<String, String> query = decodeQuery(uri);
        assertThat(uri.getScheme()).isNull();
        assertThat(uri.getHost()).isNull();
        assertThat(uri.getPath()).isEqualTo("/auth/complete");
        assertThat(query)
                .containsEntry("attempt", "A".repeat(22))
                .containsEntry("returnTo", returnTo)
                .containsEntry("result", "success")
                .containsEntry("merge", "kept_member_vote");
    }

    @Test
    @DisplayName("취소와 실패는 merge 안내 없이 저장된 attempt만 복귀 경로에 쓴다")
    void excludesMergeOutcomeFromNonSuccessResults() {
        String location = redirects.withConsumedAttempt(
                new LoginAttempt("A".repeat(22), "/"),
                KakaoLoginCompletionRedirectBuilder.Result.CANCELLED,
                false);

        Map<String, String> query = decodeQuery(URI.create(location));
        assertThat(query)
                .containsEntry("attempt", "A".repeat(22))
                .containsEntry("returnTo", "/")
                .containsEntry("result", "cancelled")
                .doesNotContainKey("merge");
    }

    @Test
    @DisplayName("member-vote merge outcome can only accompany a successful redirect")
    void rejectsMergeOutcomeForCancellationOrFailure() {
        LoginAttempt attempt = new LoginAttempt("A".repeat(22), "/");
        for (KakaoLoginCompletionRedirectBuilder.Result result : new KakaoLoginCompletionRedirectBuilder.Result[]{
                KakaoLoginCompletionRedirectBuilder.Result.CANCELLED,
                KakaoLoginCompletionRedirectBuilder.Result.FAILED}) {
            assertThatThrownBy(() -> redirects.withConsumedAttempt(attempt, result, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Merge outcome only applies to successful login");
        }
    }

    @Test
    @DisplayName("검증할 시도가 없는 callback은 요청값 없이 고정 failed 경로로 보낸다")
    void usesFixedFailurePathWithoutVerifiedAttempt() {
        assertThat(redirects.withoutVerifiedAttempt()).isEqualTo("/auth/complete?result=failed");
    }

    @Test
    @DisplayName("완료 경로 builder는 검증되지 않은 외부 복귀 경로를 거부한다")
    void rejectsUnsafeReturnToBeforeCreatingRedirect() {
        assertThatThrownBy(() -> redirects.withConsumedAttempt(
                new LoginAttempt("A".repeat(22), "//attacker.example/path"),
                KakaoLoginCompletionRedirectBuilder.Result.SUCCESS,
                false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid returnTo");
    }

    @Test
    @DisplayName("복귀 경로의 더하기·퍼센트·한글은 URLSearchParams와 정확히 왕복한다")
    void strictlyEncodesReturnToAsOneQueryValue() {
        String returnTo = "/profile?next=a+b&literal=%25&name=%ED%95%9C%EA%B8%80&space=a%20b";

        URI uri = URI.create(redirects.withConsumedAttempt(
                new LoginAttempt("A".repeat(22), returnTo),
                KakaoLoginCompletionRedirectBuilder.Result.SUCCESS,
                false));

        assertThat(uri.getRawQuery()).contains("returnTo=%2Fprofile%3Fnext%3Da%2Bb");
        assertThat(decodeQuery(uri).get("returnTo")).isEqualTo(returnTo);
    }

    private static Map<String, String> decodeQuery(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(LinkedHashMap::new, (result, pair) -> result.put(
                        URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.length == 1 ? "" : pair[1], StandardCharsets.UTF_8)),
                        Map::putAll);
    }
}
