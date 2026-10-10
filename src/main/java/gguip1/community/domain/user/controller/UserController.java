package gguip1.community.domain.user.controller;

import gguip1.community.domain.user.dto.request.UserNicknameCheckRequest;
import gguip1.community.domain.user.dto.request.UserNicknameUpdateRequest;
import gguip1.community.domain.user.dto.request.UserProfileImageUpdateRequest;
import gguip1.community.domain.user.dto.request.UserUpdateRequest;
import gguip1.community.domain.user.dto.response.UserNicknameCheckResponse;
import gguip1.community.domain.user.dto.response.MeResponse;
import gguip1.community.domain.user.dto.response.UserResponse;
import gguip1.community.domain.user.dto.response.UserUpdateResponse;
import gguip1.community.domain.user.service.UserService;
import gguip1.community.global.auth.annotation.Auth;
import gguip1.community.global.security.CurrentActor;
import gguip1.community.global.response.ApiResponse;
import gguip1.community.global.response.ApiDataResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @Auth
    @GetMapping("/users/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMyInfo() {
        Long userId = CurrentActor.userIdOrNull();
        return ResponseEntity.ok(ApiResponse.success("get_user_success", userService.getUser(userId)));
    }

    @Auth
    @GetMapping("/me")
    public ResponseEntity<ApiDataResponse<MeResponse>> getCurrentMember() {
        UserResponse user = userService.getUser(CurrentActor.userIdOrNull());
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(new ApiDataResponse<>(new MeResponse(
                        user.userId(), user.nickname(), user.profileImageUrl())));
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<UserResponse>> getUser(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.success("get_user_success", userService.getUser(userId)));
    }

    @Auth
    @PatchMapping("/users/me")
    public ResponseEntity<ApiResponse<UserUpdateResponse>> updateMyInfo(@Valid @RequestBody UserUpdateRequest requestBody) {
        Long userId = CurrentActor.userIdOrNull();
        return ResponseEntity.ok(ApiResponse.success("update_user_success", userService.updateUser(userId, requestBody)));
    }

    @Auth
    @PatchMapping("/users/me/profile-image")
    public ResponseEntity<ApiResponse<UserUpdateResponse>> updateMyProfileImage(@Valid @RequestBody UserProfileImageUpdateRequest requestBody) {
        Long userId = CurrentActor.userIdOrNull();
        return ResponseEntity.ok(ApiResponse.success("update_profile_image_success", userService.updateUserProfileImage(userId, requestBody)));
    }

    @Auth
    @PatchMapping("/users/me/nickname")
    public ResponseEntity<ApiResponse<UserUpdateResponse>> updateMyNickname(@Valid @RequestBody UserNicknameUpdateRequest requestBody) {
        Long userId = CurrentActor.userIdOrNull();
        return ResponseEntity.ok(ApiResponse.success("update_nickname_success", userService.updateUserNickname(userId, requestBody)));
    }

    @Auth
    @DeleteMapping("/users/me")
    public ResponseEntity<Void> deleteMyAccount(HttpServletRequest httpRequest) {
        Long userId = CurrentActor.userIdOrNull();
        userService.deleteUser(userId);

        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/check-nickname")
    public ResponseEntity<ApiResponse<UserNicknameCheckResponse>> checkNickname(@Valid @RequestBody UserNicknameCheckRequest userNicknameCheckRequest) {
        return ResponseEntity.ok(ApiResponse.success("nickname_available", new UserNicknameCheckResponse(userService.existsByNickname(userNicknameCheckRequest))));
    }
}
