package com.example.agentsuite.service;

import com.example.agentsuite.jooq.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void canUseToolGroup_unix_returnsTrue() {
        assertThat(authorizationService.canUseToolGroup("unix", false)).isTrue();
    }

    @Test
    void canUseToolGroup_web_returnsTrue() {
        assertThat(authorizationService.canUseToolGroup("web", false)).isTrue();
    }

    @Test
    void canUseToolGroup_mdWriter_returnsTrue() {
        assertThat(authorizationService.canUseToolGroup("md-writer", false)).isTrue();
    }

    @Test
    void canUseToolGroup_mdWriter_adminUser_returnsTrue() {
        assertThat(authorizationService.canUseToolGroup("md-writer", true)).isTrue();
    }

    @Test
    void canUseToolGroup_nullGroup_throwsNullPointerException() {
        assertThatThrownBy(() -> authorizationService.canUseToolGroup(null, false))
                .isInstanceOf(NullPointerException.class);
    }
}
