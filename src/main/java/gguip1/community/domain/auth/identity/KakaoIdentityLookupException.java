package gguip1.community.domain.auth.identity;

/** 외부 인증 실패의 응답·원인·요청 값을 숨기는 고정 예외입니다. */
public final class KakaoIdentityLookupException extends RuntimeException {
    public KakaoIdentityLookupException() {
        super("Kakao identity lookup failed");
    }
}
