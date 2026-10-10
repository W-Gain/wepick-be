package gguip1.community.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import java.util.Map;
import java.util.function.Supplier;

/** XOR-masked token header만 받아 query/form parameter fallback을 막습니다. */
public final class HeaderOnlyXorCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
    private final XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                       Supplier<CsrfToken> deferredToken) {
        delegate.handle(request, response, deferredToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        HttpServletRequest headerOnly = new HttpServletRequestWrapper(request) {
            @Override public String getParameter(String name) { return null; }
            @Override public String[] getParameterValues(String name) { return null; }
            @Override public Map<String, String[]> getParameterMap() { return Map.of(); }
        };
        return delegate.resolveCsrfTokenValue(headerOnly, csrfToken);
    }
}
