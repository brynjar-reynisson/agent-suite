package com.example.agentsuite.filter;

import com.example.agentsuite.jooq.service.SuiteUserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
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
import java.nio.charset.StandardCharsets;

@Component
public class UserResolverFilter extends OncePerRequestFilter {

    public static final String ATTR_USER_ID = "currentUserId";
    private static final long GUEST_USER_ID = 1L;
    private static final Logger log = LoggerFactory.getLogger(UserResolverFilter.class);

    private final SuiteUserService suiteUserService;
    private final String jwtSecret;

    public UserResolverFilter(SuiteUserService suiteUserService,
                               @Value("${supabase.jwt-secret}") String jwtSecret) {
        this.suiteUserService = suiteUserService;
        this.jwtSecret = jwtSecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        request.setAttribute(ATTR_USER_ID, resolveUserId(request));
        chain.doFilter(request, response);
    }

    private long resolveUserId(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return GUEST_USER_ID;
        String token = header.substring(7);
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
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
}
