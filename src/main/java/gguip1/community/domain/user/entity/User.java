package gguip1.community.domain.user.entity;

import gguip1.community.domain.image.entity.Image;

import gguip1.community.global.entity.SoftDeleteEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class User extends SoftDeleteEntity {
    @Id
    @Column(name = "user_id", nullable = false, unique = true)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    @OneToOne
    @JoinColumn(name = "profile_image_id")
    private Image profileImage;

    @Column(name = "nickname", length = 30, unique = true)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 5)
    private UserRole role = UserRole.USER;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Builder
    public User(Image profileImage, String nickname, UserRole role, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.profileImage = profileImage;
        this.nickname = nickname;
        this.role = Objects.requireNonNullElse(role, UserRole.USER);
    }

    public static User kakaoMember(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("Active member nickname is required");
        }
        return User.builder().nickname(nickname).role(UserRole.USER).build();
    }

    public boolean isActive() {
        return status != null && status == 0;
    }

    public void updateProfile(Image profileImage, String nickname){
        this.profileImage = profileImage;
        if (nickname != null){
            this.nickname = nickname;
        }
    }

    public void updateProfileImage(Image profileImage){
        this.profileImage = profileImage;
    }

    public void updateNickname(String nickname){
        if (nickname != null){
            this.nickname = nickname;
        }
    }

    @Override
    public void softDelete(){
        this.status = 1;
        this.deletedAt = LocalDateTime.now();
        this.withdrawnAt = this.deletedAt;
        this.nickname = null;
        this.profileImage = null;
    }
}
