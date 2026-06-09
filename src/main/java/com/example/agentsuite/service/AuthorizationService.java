package com.example.agentsuite.service;

import com.example.agentsuite.jooq.repository.UserRoleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
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
     * Returns true if the given tool group is accessible for the given role.
     *
     * @param group   the tool group name (e.g. "unix", "web", "md-writer")
     * @param isAdmin whether the requesting user holds the admin role
     * @return true if the tool group is accessible
     */
    public boolean canUseToolGroup(String group, boolean isAdmin) {
        Objects.requireNonNull(group, "group must not be null");
        return switch (group) {
            case "md-writer" -> isAdmin;
            default -> true;
        };
    }

    /**
     * Returns the tool groups the given role is entitled to use.
     *
     * @param isAdmin whether the requesting user holds the admin role
     * @return list of granted tool group names
     */
    public List<String> grantedToolGroups(boolean isAdmin) {
        return isAdmin ? List.of("web", "md-writer") : List.of("web");
    }
}
