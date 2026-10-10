package gguip1.community.domain.user.dto.request;

import gguip1.community.global.validation.NicknameValidation;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record UserNicknameCheckRequest(
        @NicknameValidation
        @NotBlank
        String nickname
) {
}
