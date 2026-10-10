package gguip1.community.global.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** 현재 요청의 회원은 Spring Security Authentication 하나에서만 읽습니다. */
public final class CurrentActor {
    private CurrentActor() {
    }

    public static Optional<AuthenticatedMember> member() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedMember member)) {
            return Optional.empty();
        }
        return Optional.of(member);
    }

    public static Long userIdOrNull() {
        return member().map(AuthenticatedMember::userId).orElse(null);
    }

    public static long requireUserId() {
        return member().map(AuthenticatedMember::userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated member is unavailable"));
    }
}
