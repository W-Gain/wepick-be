package gguip1.community.domain.user.mapper;

import gguip1.community.domain.image.entity.Image;
import gguip1.community.domain.user.dto.response.UserResponse;
import gguip1.community.domain.user.dto.response.UserUpdateResponse;
import gguip1.community.domain.user.entity.User;
import gguip1.community.domain.image.storage.ImageStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserMapper {
    private final ImageStorage imageStorage;

    public UserResponse toResponse(User user) {
        String imageKey = user.getProfileImage() != null ? user.getProfileImage().getStorageKey() : null;
        String fullUrl = imageStorage.publicUrl(imageKey);

        return UserResponse.builder()
                .userId(user.getUserId())
                .profileImageUrl(fullUrl)
                .nickname(user.getNickname())
                .build();
    }

    public UserUpdateResponse toUserUpdateResponse(User user) {
        String imageKey = user.getProfileImage() != null ? user.getProfileImage().getStorageKey() : null;
        String fullUrl = imageStorage.publicUrl(imageKey);

        return UserUpdateResponse.builder()
                .userId(user.getUserId())
                .profileImageUrl(fullUrl)
                .nickname(user.getNickname())
                .build();
    }
}
