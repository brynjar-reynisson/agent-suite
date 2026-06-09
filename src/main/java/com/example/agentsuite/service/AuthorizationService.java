package com.example.agentsuite.service;

import com.example.agentsuite.jooq.repository.UserRoleRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class AuthorizationService {

    private final UserRoleRepository userRoleRepository;

    public AuthorizationService(UserRoleRepository userRoleRepository) {
        this.userRoleRepository = userRoleRepository;
    }

    public boolean isAdmin(long userId) {
        return userRoleRepository.isAdmin(userId);
    }

    /**
     * Returns true if the given tool group is accessible.
     *
     * @param group the tool group name (e.g. "unix", "web", "md-writer")
     * @param isAdmin reserved for future gating — currently has no effect; all groups return true
     * @return true if the tool group is accessible
     */
    public boolean canUseToolGroup(String group, boolean isAdmin) {
        Objects.requireNonNull(group, "group must not be null");

        // All groups open to all users today.
        // Future: case "md-writer" -> isAdmin; case "mcp" -> isAdmin; default -> true;
        return true;
    }
}
