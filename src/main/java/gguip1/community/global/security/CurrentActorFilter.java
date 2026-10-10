package gguip1.community.global.security;

import gguip1.community.domain.user.entity.User;
import gguip1.community.domain.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** 세션 principal을 매 요청 ACTIVE 회원 행과 대조하고 탈퇴 계정 인증을 폐기합니다. */
public final class CurrentActorFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final SecurityContextRepository contexts;

    public CurrentActorFilter(UserRepository users, SecurityContextRepository contexts) {
        this.users = users;
        this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication authentication = context.getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedMember principal) {
            User active = users.findById(principal.userId()).orElse(null);
            if (active == null || !active.isActive()) {
                context.setAuthentication(null);
                contexts.saveContext(context, request, response);
            } else if (active.getRole() != principal.role()) {
                AuthenticatedMember refreshed = new AuthenticatedMember(active.getUserId(), active.getRole());
                context.setAuthentication(authenticated(refreshed));
                contexts.saveContext(context, request, response);
            }
        } else if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication.getPrincipal() instanceof String)) {
            context.setAuthentication(null);
            contexts.saveContext(context, request, response);
        }
        chain.doFilter(request, response);
    }

    static UsernamePasswordAuthenticationToken authenticated(AuthenticatedMember member) {
        return UsernamePasswordAuthenticationToken.authenticated(member, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + member.role().name())));
    }
}
