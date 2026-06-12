package com.example.agentsuite.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecretValidatorTest {

    @Test
    void check_blankSecret_throws() {
        assertThatThrownBy(() -> JwtSecretValidator.check("", List.of("dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supabase.jwt-secret");
    }

    @Test
    void check_nullSecret_throws() {
        assertThatThrownBy(() -> JwtSecretValidator.check(null, List.of("dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supabase.jwt-secret");
    }

    @Test
    void check_knownInsecureDefaultUnderProd_throws() {
        assertThatThrownBy(() -> JwtSecretValidator.check(
                JwtSecretValidator.KNOWN_INSECURE_DEFAULT, List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default");
    }

    @Test
    void check_knownInsecureDefaultUnderDev_ok() {
        // Local Supabase signs tokens with this fixed secret, so dev legitimately uses it.
        assertThatCode(() -> JwtSecretValidator.check(
                JwtSecretValidator.KNOWN_INSECURE_DEFAULT, List.of("dev")))
                .doesNotThrowAnyException();
    }

    @Test
    void check_realSecretUnderProd_ok() {
        assertThatCode(() -> JwtSecretValidator.check(
                "a-real-strong-prod-secret-value-32chars+", List.of("prod")))
                .doesNotThrowAnyException();
    }
}
