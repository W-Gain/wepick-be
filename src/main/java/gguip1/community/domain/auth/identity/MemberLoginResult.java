package gguip1.community.domain.auth.identity;

import gguip1.community.domain.user.entity.UserRole;

import java.util.Objects;

/** 세션 발급에 필요한 내부 ID·권한과 공개 merge 안내만 전달합니다. */
public record MemberLoginResult(long userId, UserRole role, boolean keptMemberVote) {
    public MemberLoginResult {
        if (userId <= 0) throw new IllegalArgumentException("Invalid internal member ID");
        Objects.requireNonNull(role, "role");
    }

    @Override
    public String toString() {
        return "MemberLoginResult[member=redacted, role=" + role + ", keptMemberVote=" + keptMemberVote + "]";
    }
}
