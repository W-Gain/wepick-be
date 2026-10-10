package gguip1.community.domain.user.dto.request;

import gguip1.community.global.validation.NicknameValidation;

public record UserNicknameUpdateRequest(
        @NicknameValidation
        String nickname
){
}
