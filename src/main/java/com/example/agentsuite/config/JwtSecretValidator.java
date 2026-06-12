package com.example.agentsuite.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Fails application startup fast when the Supabase JWT secret is misconfigured, so the app never
 * silently runs with a missing or known-insecure signing secret (which would make forged tokens
 * trivially acceptable). The secret must be supplied by the environment, never committed.
 */
@Component
public class JwtSecretValidator {

    static final String KNOWN_INSECURE_DEFAULT = "super-secret-jwt-token-with-at-least-32-characters-long";

    private final String secret;
    private final List<String> activeProfiles;

    public JwtSecretValidator(@Value("${supabase.jwt-secret:}") String secret, Environment environment) {
        this.secret = secret;
        String[] active = environment.getActiveProfiles();
        this.activeProfiles = active.length > 0
                ? Arrays.asList(active)
                : Arrays.asList(environment.getDefaultProfiles());
    }

    @PostConstruct
    void validateOnStartup() {
        check(secret, activeProfiles);
    }

    static void check(String secret, Collection<String> activeProfiles) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "supabase.jwt-secret is not configured. Set the SUPABASE_JWT_SECRET (dev: your local "
                    + "Supabase JWT secret) or SUPABASE_PROD_JWT_SECRET (prod) environment variable before starting.");
        }
        if (activeProfiles.contains("prod") && KNOWN_INSECURE_DEFAULT.equals(secret)) {
            throw new IllegalStateException(
                    "supabase.jwt-secret is set to the well-known local Supabase default while the 'prod' profile "
                    + "is active. Configure SUPABASE_PROD_JWT_SECRET with the real project JWT secret.");
        }
    }
}
