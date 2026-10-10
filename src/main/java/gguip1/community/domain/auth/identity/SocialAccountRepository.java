package gguip1.community.domain.auth.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {
    Optional<SocialAccount> findByProviderAndProviderUserId(
            SocialAccountProvider provider,
            String providerUserId);

    void deleteByUserUserId(long userId);
}
