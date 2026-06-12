package com.example.agentsuite.filter;

import com.example.agentsuite.jooq.service.SuiteUserService;
import com.example.agentsuite.service.AuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserResolverFilterTest {

    private static final String SECRET = "super-secret-jwt-token-with-at-least-32-characters-long";
    private static final String SUPABASE_URL = "http://127.0.0.1:54321";
    private static final String ISSUER = SUPABASE_URL + "/auth/v1";
    private static final String AUDIENCE = "authenticated";
    private static final long GUEST_USER_ID = 1L;

    private SuiteUserService suiteUserService;
    private AuthorizationService authorizationService;
    private UserResolverFilter filter;

    @BeforeEach
    void setUp() {
        suiteUserService = mock(SuiteUserService.class);
        authorizationService = mock(AuthorizationService.class);
        when(authorizationService.isAdmin(anyLong())).thenReturn(false);
        filter = new UserResolverFilter(suiteUserService, authorizationService, SECRET, SUPABASE_URL, "", new ObjectMapper());
    }

    private String makeHs256Jwt(String sub, String email, boolean expired) {
        Date exp = expired
                ? new Date(System.currentTimeMillis() - 1_000)
                : new Date(System.currentTimeMillis() + 3_600_000);
        return Jwts.builder()
                .setSubject(sub)
                .claim("email", email)
                .setIssuer(ISSUER)
                .setAudience(AUDIENCE)
                .setExpiration(exp)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void noAuthHeader_setsGuestUserId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_USER_ID)).isEqualTo(GUEST_USER_ID);
    }

    @Test
    void validHs256Jwt_setsResolvedUserId() throws Exception {
        when(suiteUserService.findOrCreate("uuid-abc", "user@example.com")).thenReturn(42L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + makeHs256Jwt("uuid-abc", "user@example.com", false));
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_USER_ID)).isEqualTo(42L);
    }

    @Test
    void validEs256Jwt_setsResolvedUserId() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        filter.setCachedPublicKey(kp.getPublic());

        String token = Jwts.builder()
                .setSubject("uuid-es256")
                .claim("email", "es256@example.com")
                .setIssuer(ISSUER)
                .setAudience(AUDIENCE)
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(kp.getPrivate(), SignatureAlgorithm.ES256)
                .compact();

        when(suiteUserService.findOrCreate("uuid-es256", "es256@example.com")).thenReturn(99L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_USER_ID)).isEqualTo(99L);
    }

    @Test
    void invalidSignature_fallsBackToGuest() throws Exception {
        String badToken = Jwts.builder()
                .setSubject("uuid-xyz")
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(
                        "wrong-secret-padded-to-at-least-32-chars-xx".getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + badToken);
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_USER_ID)).isEqualTo(GUEST_USER_ID);
    }

    @Test
    void expiredJwt_fallsBackToGuest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + makeHs256Jwt("uuid-abc", "user@example.com", true));
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_USER_ID)).isEqualTo(GUEST_USER_ID);
    }

    @Test
    void wrongIssuer_fallsBackToGuest() throws Exception {
        when(suiteUserService.findOrCreate("uuid-abc", "user@example.com")).thenReturn(42L);
        String token = Jwts.builder()
                .setSubject("uuid-abc")
                .claim("email", "user@example.com")
                .setIssuer("https://evil.example.com/auth/v1")
                .setAudience(AUDIENCE)
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_USER_ID)).isEqualTo(GUEST_USER_ID);
    }

    @Test
    void wrongAudience_fallsBackToGuest() throws Exception {
        when(suiteUserService.findOrCreate("uuid-abc", "user@example.com")).thenReturn(42L);
        String token = Jwts.builder()
                .setSubject("uuid-abc")
                .claim("email", "user@example.com")
                .setIssuer(ISSUER)
                .setAudience("anon")
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_USER_ID)).isEqualTo(GUEST_USER_ID);
    }

    @Test
    void unsupportedAlgHs384_fallsBackToGuest() throws Exception {
        when(suiteUserService.findOrCreate("uuid-abc", "user@example.com")).thenReturn(42L);
        String token = Jwts.builder()
                .setSubject("uuid-abc")
                .claim("email", "user@example.com")
                .setIssuer(ISSUER)
                .setAudience(AUDIENCE)
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS384)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_USER_ID)).isEqualTo(GUEST_USER_ID);
    }

    @Test
    void noAuthHeader_setsIsAdminFalse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN)).isEqualTo(false);
    }

    @Test
    void validHs256Jwt_regularUser_setsIsAdminFalse() throws Exception {
        when(suiteUserService.findOrCreate("uuid-abc", "user@example.com")).thenReturn(42L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + makeHs256Jwt("uuid-abc", "user@example.com", false));
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN)).isEqualTo(false);
    }

    @Test
    void validHs256Jwt_adminUser_setsIsAdminTrue() throws Exception {
        when(suiteUserService.findOrCreate("admin-uuid", "admin@example.com")).thenReturn(99L);
        when(authorizationService.isAdmin(99L)).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + makeHs256Jwt("admin-uuid", "admin@example.com", false));
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN)).isEqualTo(true);
    }

    @Test
    void isAdminThrows_setsIsAdminFalseAndContinuesChain() throws Exception {
        when(suiteUserService.findOrCreate("uuid-throws", "throws@example.com")).thenReturn(77L);
        when(authorizationService.isAdmin(77L)).thenThrow(new RuntimeException("DB down"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockFilterChain chain = new MockFilterChain();
        request.addHeader("Authorization", "Bearer " + makeHs256Jwt("uuid-throws", "throws@example.com", false));
        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);
        assertThat(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN)).isEqualTo(false);
        assertThat(chain.getRequest()).isNotNull();
    }
}
