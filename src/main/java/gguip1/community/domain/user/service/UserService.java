package gguip1.community.domain.user.service;

import gguip1.community.domain.image.entity.Image;
import gguip1.community.domain.image.repository.ImageRepository;
import gguip1.community.domain.user.dto.request.UserNicknameCheckRequest;
import gguip1.community.domain.user.dto.request.UserNicknameUpdateRequest;
import gguip1.community.domain.user.dto.request.UserProfileImageUpdateRequest;
import gguip1.community.domain.user.dto.request.UserUpdateRequest;
import gguip1.community.domain.user.dto.response.UserResponse;
import gguip1.community.domain.user.dto.response.UserUpdateResponse;
import gguip1.community.domain.user.entity.User;
import gguip1.community.domain.auth.identity.SocialAccountRepository;
import gguip1.community.domain.user.mapper.UserMapper;
import gguip1.community.domain.user.repository.UserRepository;
import gguip1.community.global.validation.NicknamePolicy;
import gguip1.community.global.exception.ErrorCode;
import gguip1.community.global.exception.ErrorException;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final JdbcTemplate jdbc;

    private final UserMapper userMapper;

    // users/{userId} (관리자 등 타인) 정보 조회
    public UserResponse getUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ErrorException(ErrorCode.USER_NOT_FOUND));
        return userMapper.toResponse(user);
    }

    // users/{userId} (관리자 등 타인) 정보 수정
    @Transactional
    public UserUpdateResponse updateUser(Long userId, UserUpdateRequest request){
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ErrorException(ErrorCode.USER_NOT_FOUND));

        Image profileImage = null;
        if (request.profileImageId() != null){
            profileImage = imageRepository.findById(request.profileImageId())
                    .orElseThrow(() -> new ErrorException(ErrorCode.NOT_FOUND));
        }

        String nickname = request.nickname() == null ? null : normalizeNickname(request.nickname());
        if (nickname != null){
            if (!nickname.equals(user.getNickname())) {
                if (userRepository.existsByNickname(nickname)) {
                    throw new ErrorException(ErrorCode.DUPLICATE_NICKNAME);
                }
            }
        }

        user.updateProfile(profileImage, nickname);

        userRepository.save(user);

        return userMapper.toUserUpdateResponse(user);
    }

    @Transactional
    public UserUpdateResponse updateUserProfileImage(Long userId, @Valid UserProfileImageUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ErrorException(ErrorCode.USER_NOT_FOUND));

        Image profileImage = null;
        if (request.profileImageId() != null){
            profileImage = imageRepository.findById(request.profileImageId())
                    .orElseThrow(() -> new ErrorException(ErrorCode.NOT_FOUND));
        }

        user.updateProfileImage(profileImage);

        userRepository.save(user);

        return userMapper.toUserUpdateResponse(user);
    }

    @Transactional
    public UserUpdateResponse updateUserNickname(Long userId, @Valid UserNicknameUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ErrorException(ErrorCode.USER_NOT_FOUND));

        String nickname = normalizeNickname(request.nickname());

        if (!nickname.equals(user.getNickname())) {
            if (userRepository.existsByNickname(nickname)) {
                throw new ErrorException(ErrorCode.DUPLICATE_NICKNAME);
            }
        }

        user.updateNickname(nickname);

        userRepository.save(user);

        return userMapper.toUserUpdateResponse(user);
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findActiveForUpdate(userId)
                .orElseThrow(() -> new ErrorException(ErrorCode.USER_NOT_FOUND));

        socialAccountRepository.deleteByUserUserId(userId);
        jdbc.update("UPDATE anonymous_voters SET linked_user_id = NULL WHERE linked_user_id = ?", userId);
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", Long.toString(userId));
        user.softDelete();

        userRepository.save(user);
    }

    public boolean existsByNickname(UserNicknameCheckRequest request) {
        return userRepository.existsByNickname(normalizeNickname(request.nickname()));
    }

    private static String normalizeNickname(String value) {
        try {
            return NicknamePolicy.normalize(value);
        } catch (IllegalArgumentException failure) {
            throw new ErrorException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
