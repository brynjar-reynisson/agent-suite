package com.example.agentsuite.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RootDirectoriesTest {

    @Test
    void allowed_containsEmptyStringForNoRootSelected() {
        assertThat(RootDirectories.ALLOWED).contains("");
    }

    @Test
    void nonEmpty_excludesEmptyString_keepsAllRealDirectories() {
        assertThat(RootDirectories.nonEmpty())
                .doesNotContain("")
                .hasSize(RootDirectories.ALLOWED.size() - 1)
                .allSatisfy(dir -> assertThat(RootDirectories.ALLOWED).contains(dir));
    }
}
