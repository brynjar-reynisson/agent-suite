package com.example.agentsuite.filter;

import com.example.agentsuite.jooq.service.SuiteUserService;
import com.example.agentsuite.service.AuthorizationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

@Component
public class UserResolverFilter extends OncePerRequestFilter {

    public static final String ATTR_USER_ID = "currentUserId";
    public static final String ATTR_IS_ADMIN = "currentUserIsAdmin";
    // Assumes the Guest row always holds user_id = 1 (first insert, sequence starts at 1).
    // The isAdmin DB lookup is skipped for this id — if the row is ever deleted and re-inserted
    // it would receive a new id and this guard would need updating.
    private static final long GUEST_USER_ID = 1L;
    // Supabase user tokens carry aud="authenticated"; reject anything else (anon/service/foreign tokens).
    private static final String EXPECTED_AUDIENCE = "authenticated";
    private static final Logger log = LoggerFactory.getLogger(UserResolverFilter.class);

    private final SuiteUserService suiteUserService;
    private final AuthorizationService authorizationService;
    private final String jwtSecret;
    private final String supabaseUrl;
    private final String expectedIssuer;
    private final ObjectMapper objectMapper;

    private volatile PublicKey cachedPublicKey;

    public UserResolverFilter(SuiteUserService suiteUserService,
                               AuthorizationService authorizationService,
                               @Value("${supabase.jwt-secret}") String jwtSecret,
                               @Value("${supabase.url}") String supabaseUrl,
                               @Value("${supabase.jwt-issuer:}") String issuerOverride,
                               ObjectMapper objectMapper) {
        this.suiteUserService = suiteUserService;
        this.authorizationService = authorizationService;
        this.jwtSecret = jwtSecret;
        this.supabaseUrl = supabaseUrl;
        // Default to the Supabase convention <supabase.url>/auth/v1; overridable if the issuer string differs.
        this.expectedIssuer = (issuerOverride != null && !issuerOverride.isBlank())
                ? issuerOverride
                : supabaseUrl + "/auth/v1";
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        long userId = resolveUserId(request);
        request.setAttribute(ATTR_USER_ID, userId);
        boolean isAdmin = false;
        if (userId != GUEST_USER_ID) {
            try {
                isAdmin = authorizationService.isAdmin(userId);
            } catch (Exception e) {
                log.error("Failed to load roles for user {}, defaulting to non-admin", userId, e);
            }
        }
        request.setAttribute(ATTR_IS_ADMIN, isAdmin);
        chain.doFilter(request, response);
    }

    private long resolveUserId(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return GUEST_USER_ID;
        String token = header.substring(7);
        try {
            Claims claims = Jwts.parserBuilder()
                    .requireIssuer(expectedIssuer)
                    .requireAudience(EXPECTED_AUDIENCE)
                    .setSigningKeyResolver(new SigningKeyResolverAdapter() {
                        @Override
                        @SuppressWarnings("rawtypes")
                        public Key resolveSigningKey(JwsHeader header, Claims claims) {
                            // Pin to the two algorithms Supabase actually issues. HS256 uses the shared
                            // project secret; ES256 uses the asymmetric public key from JWKS. No other
                            // algorithm (incl. HS384/512 and "none") is accepted.
                            return switch (header.getAlgorithm()) {
                                case "HS256" -> Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
                                case "ES256" -> getOrFetchPublicKey();
                                default -> throw new UnsupportedJwtException(
                                        "Unsupported algorithm: " + header.getAlgorithm());
                            };
                        }
                    })
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            String sub = claims.getSubject();
            String email = claims.get("email", String.class);
            return suiteUserService.findOrCreate(sub, email);
        } catch (JwtException e) {
            log.warn("Invalid JWT, falling back to guest: {}", e.getMessage());
            return GUEST_USER_ID;
        } catch (Exception e) {
            log.error("Failed to resolve user from JWT, falling back to guest: {}", e.getMessage());
            return GUEST_USER_ID;
        }
    }

    private PublicKey getOrFetchPublicKey() {
        if (cachedPublicKey == null) {
            synchronized (this) {
                if (cachedPublicKey == null) {
                    cachedPublicKey = fetchPublicKeyFromJwks();
                }
            }
        }
        return cachedPublicKey;
    }

    // package-private for testing
    void setCachedPublicKey(PublicKey key) {
        this.cachedPublicKey = key;
    }

    private PublicKey fetchPublicKeyFromJwks() {
        try {
            String url = supabaseUrl + "/auth/v1/.well-known/jwks.json";
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode keys = objectMapper.readTree(resp.body()).get("keys");
            for (JsonNode key : keys) {
                if ("EC".equals(key.get("kty").asText())) {
                    return parseEcPublicKey(key);
                }
            }
            throw new IllegalStateException("No EC key found in JWKS at " + url);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch JWKS from Supabase: " + e.getMessage(), e);
        }
    }

    private static PublicKey parseEcPublicKey(JsonNode keyNode) throws Exception {
        byte[] xBytes = Base64.getUrlDecoder().decode(keyNode.get("x").asText());
        byte[] yBytes = Base64.getUrlDecoder().decode(keyNode.get("y").asText());
        ECPoint w = new ECPoint(new BigInteger(1, xBytes), new BigInteger(1, yBytes));
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1")); // P-256
        ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, ecSpec));
    }
}
