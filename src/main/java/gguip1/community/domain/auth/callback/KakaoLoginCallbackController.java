package gguip1.community.domain.auth.callback;

import gguip1.community.domain.auth.identity.AnonymousVoterCookie;
import gguip1.community.domain.auth.identity.AnonymousVoterCookieProperties;
import gguip1.community.domain.auth.identity.KakaoIdentity;
import gguip1.community.domain.auth.identity.KakaoIdentityClient;
import gguip1.community.domain.auth.identity.MemberLoginResult;
import gguip1.community.domain.auth.identity.MemberLoginService;
import gguip1.community.domain.auth.start.LoginBrowserBindingCookie;
import gguip1.community.global.security.SessionCommitter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** state 소비부터 callback redirect까지 외부 I/O·DB commit·session commit 경계를 연결합니다. */
@RestController
@RequestMapping("/auth/kakao")
public class KakaoLoginCallbackController {
    private final KakaoLoginCallbackCoordinator callbacks;
    private final LoginBrowserBindingCookie bindingCookies;
    private final AnonymousVoterCookieProperties anonymousCookies;
    private final KakaoIdentityClient identities;
    private final MemberLoginService members;
    private final SessionCommitter sessions;
    private final KakaoLoginCompletionRedirectBuilder redirects;

    public KakaoLoginCallbackController(KakaoLoginCallbackCoordinator callbacks,
                                        LoginBrowserBindingCookie bindingCookies,
                                        AnonymousVoterCookieProperties anonymousCookies,
                                        KakaoIdentityClient identities,
                                        MemberLoginService members,
                                        SessionCommitter sessions,
                                        KakaoLoginCompletionRedirectBuilder redirects) {
        this.callbacks = callbacks;
        this.bindingCookies = bindingCookies;
        this.anonymousCookies = anonymousCookies;
        this.identities = identities;
        this.members = members;
        this.sessions = sessions;
        this.redirects = redirects;
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(HttpServletRequest request, HttpServletResponse response) {
        Optional<KakaoLoginCallbackDecision> verified;
        try {
            String binding = bindingCookies.requirePrepared(request);
            verified = callbacks.consume(
                    request.getParameterValues("state"), binding,
                    request.getParameterValues("code"), request.getParameterValues("error"));
        } catch (RuntimeException ignored) {
            return redirect(redirects.withoutVerifiedAttempt());
        }
        if (verified.isEmpty()) return redirect(redirects.withoutVerifiedAttempt());

        KakaoLoginCallbackDecision decision = verified.orElseThrow();
        if (decision.outcome() == KakaoLoginCallbackDecision.Outcome.CANCELLED) {
            return redirect(redirects.withConsumedAttempt(decision.attempt(),
                    KakaoLoginCompletionRedirectBuilder.Result.CANCELLED, false));
        }
        if (decision.outcome() == KakaoLoginCallbackDecision.Outcome.FAILED) {
            return redirect(redirects.withConsumedAttempt(decision.attempt(),
                    KakaoLoginCompletionRedirectBuilder.Result.FAILED, false));
        }

        MemberLoginResult member;
        try {
            // state는 이미 commit된 상태이며 provider HTTP는 회원 DB transaction 밖에서 한 번만 수행합니다.
            KakaoIdentity identity = identities.exchangeCodeAndLoadIdentity(
                    decision.authorizationCode(), decision.consumedState());
            member = members.login(identity, anonymousTokenHash(request, anonymousCookies.cookieName()));
        } catch (RuntimeException ignored) {
            return redirect(redirects.withConsumedAttempt(decision.attempt(),
                    KakaoLoginCompletionRedirectBuilder.Result.FAILED, false));
        }

        try {
            sessions.commit(member, request, response);
        } catch (RuntimeException ignored) {
            sessions.clearAfterFailure(request, response);
            return redirect(redirects.withConsumedAttempt(decision.attempt(),
                    KakaoLoginCompletionRedirectBuilder.Result.FAILED, false));
        }
        return redirect(redirects.withConsumedAttempt(decision.attempt(),
                KakaoLoginCompletionRedirectBuilder.Result.SUCCESS, member.keptMemberVote()));
    }

    private static String anonymousTokenHash(HttpServletRequest request, String cookieName) {
        List<String> rawValues = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders("Cookie"))) {
            for (String pair : header.split(";", -1)) {
                int equals = pair.indexOf('=');
                String name = (equals < 0 ? pair : pair.substring(0, equals)).trim();
                if (cookieName.equals(name)) {
                    rawValues.add(equals < 0 ? "" : pair.substring(equals + 1).trim());
                }
            }
        }
        if (rawValues.size() != 1) return null;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        String[] values = Arrays.stream(cookies)
                .filter(cookie -> cookieName.equals(cookie.getName()))
                .map(Cookie::getValue)
                .toArray(String[]::new);
        return values.length == 1 && values[0].equals(rawValues.getFirst())
                ? AnonymousVoterCookie.hashIfValid(values[0]) : null;
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(302)
                .location(URI.create(location))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }
}
