package com.example.agentsuite.filter;

import com.example.agentsuite.jooq.service.SuiteUserService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserResolverFilterTest {

    private static final String SECRET = "super-secret-jwt-token-with-at-least-32-characters-long";
    private static final long GUEST_USER_ID = 1L;

    private SuiteUserService suiteUserService;
    private UserResolverFilter filter;

    @BeforeEach
    void setUp() {
        suiteUserService = mock(SuiteUserService.class);
        filter = new UserResolverFilter(suiteUserService, SECRET);
    }

    private String makeJwt(String sub, String email, boolean expired) {
        Date exp = expired
                ? new Date(System.currentTimeMillis() - 1_000)
                : new Date(System.currentTimeMillis() + 3_600_000);
        return Jwts.builder()
                .setSubject(sub)
                .claim("email", email)
                .setExpiration(exp)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void noAuthHeader_setsGuestUserId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_USER_ID)).isEqualTo(GUEST_USER_ID);
    }

    @Test
    void validJwt_setsResolvedUserId() throws Exception {
        when(suiteUserService.findOrCreate("uuid-abc", "user@example.com")).thenReturn(42L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + makeJwt("uuid-abc", "user@example.com", false));
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_USER_ID)).isEqualTo(42L);
    }

    @Test
    void invalidSignature_fallsBackToGuest() throws Exception {
        String badToken = Jwts.builder()
                .setSubject("uuid-xyz")
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(
                        "wrong-secret-padded-to-at-least-32-chars-xx".getBytes(StandardCharsets.UTF_8)))
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + badToken);
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_USER_ID)).isEqualTo(GUEST_USER_ID);
    }

    @Test
    void expiredJwt_fallsBackToGuest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + makeJwt("uuid-abc", "user@example.com", true));
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_USER_ID)).isEqualTo(GUEST_USER_ID);
    }
}
