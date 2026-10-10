package gguip1.community.domain.auth.identity;

/** Kakao에서 검증된 내부 연결용 회원번호만 보관합니다. */
public record KakaoIdentity(long providerUserId) {
    public KakaoIdentity {
        if (providerUserId <= 0) {
            throw new IllegalArgumentException("Kakao identity is invalid");
        }
    }

    @Override
    public String toString() {
        return "KakaoIdentity[redacted]";
    }
}
