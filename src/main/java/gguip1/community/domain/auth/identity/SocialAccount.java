package gguip1.community.domain.auth.identity;

import gguip1.community.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.Objects;

/** V2 social_accounts의 외부 식별자를 내부 회원 ID와 분리해 매핑합니다. */
@Entity
@Table(name = "social_accounts", uniqueConstraints = {
        @UniqueConstraint(name = "UK_social_accounts_provider_identity", columnNames = {"provider", "provider_user_id"}),
        @UniqueConstraint(name = "UK_social_accounts_user_provider", columnNames = {"user_id", "provider"})
})
public class SocialAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "social_account_id", nullable = false)
    private Long socialAccountId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private SocialAccountProvider provider;

    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    @Column(name = "connected_at", nullable = false, updatable = false)
    private LocalDateTime connectedAt;

    protected SocialAccount() {
    }

    private SocialAccount(User user, SocialAccountProvider provider, String providerUserId, LocalDateTime connectedAt) {
        this.user = Objects.requireNonNull(user, "user");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.providerUserId = Objects.requireNonNull(providerUserId, "providerUserId");
        this.connectedAt = Objects.requireNonNull(connectedAt, "connectedAt");
    }

    public static SocialAccount kakao(User user, long kakaoUserId, LocalDateTime connectedAt) {
        if (kakaoUserId <= 0) {
            throw new IllegalArgumentException("Kakao provider user ID must be positive");
        }
        return new SocialAccount(user, SocialAccountProvider.KAKAO, Long.toString(kakaoUserId), connectedAt);
    }

    public Long getSocialAccountId() {
        return socialAccountId;
    }

    public User getUser() {
        return user;
    }

    public SocialAccountProvider getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public LocalDateTime getConnectedAt() {
        return connectedAt;
    }

    @Override
    public String toString() {
        return "SocialAccount[provider=" + provider + ", providerUserId=redacted]";
    }
}
