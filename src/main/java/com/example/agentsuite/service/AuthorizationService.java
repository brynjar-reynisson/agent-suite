package com.example.agentsuite.service;

import com.example.agentsuite.jooq.repository.UserRoleRepository;
import org.springframework.stereotype.Service;

import java.util.List;

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
     * Returns the tool groups the given role is entitled to use.
     *
     * @param isAdmin whether the requesting user holds the admin role
     * @return list of granted tool group names
     */
    public List<String> grantedToolGroups(boolean isAdmin) {
        return isAdmin ? List.of("web", "md-writer", "mcp") : List.of("web");
    }
}
