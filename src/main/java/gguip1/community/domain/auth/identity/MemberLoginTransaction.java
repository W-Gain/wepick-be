package gguip1.community.domain.auth.identity;

import gguip1.community.domain.user.entity.User;
import gguip1.community.domain.user.entity.UserRole;
import gguip1.community.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** Kakao identity 연결·신규 회원 생성·익명 표 병합을 함께 커밋합니다. */
@Service
public class MemberLoginTransaction {
    private static final int MAX_NICKNAME_ATTEMPTS = 5;
    private final UserRepository users;
    private final SocialAccountRepository socialAccounts;
    private final MemberNicknameGenerator nicknames;
    private final AnonymousVoteMergeService votes;
    private final Clock clock;

    public MemberLoginTransaction(UserRepository users, SocialAccountRepository socialAccounts,
                                  MemberNicknameGenerator nicknames, AnonymousVoteMergeService votes, Clock clock) {
        this.users = Objects.requireNonNull(users, "users");
        this.socialAccounts = Objects.requireNonNull(socialAccounts, "socialAccounts");
        this.nicknames = Objects.requireNonNull(nicknames, "nicknames");
        this.votes = Objects.requireNonNull(votes, "votes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public MemberLoginResult resolveAndMerge(KakaoIdentity identity, String anonymousTokenHash) {
        String providerUserId = Long.toString(identity.providerUserId());
        SocialAccount account = socialAccounts
                .findByProviderAndProviderUserId(SocialAccountProvider.KAKAO, providerUserId)
                .orElse(null);
        User user;
        if (account != null) {
            user = users.findActiveForUpdate(account.getUser().getUserId())
                    .orElseThrow(MemberLoginFailure::new);
        } else {
            user = createMember();
            socialAccounts.saveAndFlush(SocialAccount.kakao(user, identity.providerUserId(),
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
            user = users.findActiveForUpdate(user.getUserId()).orElseThrow(MemberLoginFailure::new);
        }
        boolean keptMemberVote = votes.merge(user.getUserId(), anonymousTokenHash);
        return new MemberLoginResult(user.getUserId(), user.getRole(), keptMemberVote);
    }

    private User createMember() {
        for (int attempt = 0; attempt < MAX_NICKNAME_ATTEMPTS; attempt++) {
            String nickname = nicknames.generate();
            if (users.existsByNickname(nickname)) continue;
            return users.saveAndFlush(User.kakaoMember(nickname));
        }
        throw new MemberLoginFailure();
    }

    public static final class MemberLoginFailure extends RuntimeException {
        public MemberLoginFailure() {
            super("Member login could not be completed");
        }
    }
}
