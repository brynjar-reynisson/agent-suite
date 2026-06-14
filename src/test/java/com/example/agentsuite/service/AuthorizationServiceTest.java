package com.example.agentsuite.service;

import com.example.agentsuite.jooq.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthorizationServiceTest {

    private UserRoleRepository userRoleRepository;
    private AuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        userRoleRepository = mock(UserRoleRepository.class);
        authorizationService = new AuthorizationService(userRoleRepository);
    }

    @Test
    void isAdmin_delegatesToRepository_returnsTrue() {
        when(userRoleRepository.isAdmin(42L)).thenReturn(true);
        assertThat(authorizationService.isAdmin(42L)).isTrue();
    }

    @Test
    void isAdmin_nonAdminUser_returnsFalse() {
        when(userRoleRepository.isAdmin(1L)).thenReturn(false);
        assertThat(authorizationService.isAdmin(1L)).isFalse();
    }

    @Test
    void grantedToolGroups_nonAdmin_returnsWebOnly() {
        assertThat(authorizationService.grantedToolGroups(false)).containsExactly("web");
    }

    @Test
    void grantedToolGroups_admin_returnsWebMdWriterMcpAndAudio() {
        assertThat(authorizationService.grantedToolGroups(true)).containsExactly("web", "md-writer", "mcp", "audio");
    }

    @Test
    void grantedToolGroups_admin_includesAudio() {
        assertThat(authorizationService.grantedToolGroups(true)).contains("audio");
    }

    @Test
    void grantedToolGroups_nonAdmin_doesNotIncludeAudio() {
        assertThat(authorizationService.grantedToolGroups(false)).doesNotContain("audio");
    }
}
