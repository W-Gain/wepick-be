package gguip1.community.global.security;

import gguip1.community.domain.user.entity.UserRole;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;

/** 서버 세션에는 내부 회원 ID와 권한만 직렬화합니다. */
public record AuthenticatedMember(long userId, UserRole role) implements Principal, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public AuthenticatedMember {
        if (userId <= 0) throw new IllegalArgumentException("Invalid internal member ID");
        Objects.requireNonNull(role, "role");
    }

    @Override
    public String getName() {
        return Long.toString(userId);
    }

    @Override
    public String toString() {
        return "AuthenticatedMember[member=redacted, role=" + role + "]";
    }
}
