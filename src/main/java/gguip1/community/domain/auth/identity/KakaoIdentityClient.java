package gguip1.community.domain.auth.identity;

/** 로그인 coordinator가 DB 트랜잭션 밖에서 호출하는 Kakao 외부 식별 어댑터입니다. */
public interface KakaoIdentityClient {
    /** 이미 소비된 state와 인가 code로 회원번호만 조회합니다. */
    KakaoIdentity exchangeCodeAndLoadIdentity(String authorizationCode, String consumedState);
}
