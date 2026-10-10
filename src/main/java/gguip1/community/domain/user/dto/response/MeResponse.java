package gguip1.community.domain.user.dto.response;

/** API §3의 현재 회원 계약입니다. */
public record MeResponse(Long id, String nickname, String profileImageUrl) {
}
