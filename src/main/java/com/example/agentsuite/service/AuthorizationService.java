package com.example.agentsuite.service;

import com.example.agentsuite.jooq.repository.UserRoleRepository;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {

    private final UserRoleRepository userRoleRepository;

    public AuthorizationService(UserRoleRepository userRoleRepository) {
        this.userRoleRepository = userRoleRepository;
    }

    public boolean isAdmin(long userId) {
        return userRoleRepository.isAdmin(userId);
    }

    // All groups open to all users today.
    // Future: case "md-writer" -> isAdmin; case "mcp" -> isAdmin;
    public boolean canUseToolGroup(String group, boolean isAdmin) {
        return true;
    }
}
