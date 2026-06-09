package com.example.agentsuite.jooq.repository;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import static com.example.agentsuite.jooq.generated.Tables.USER_ROLE;

@Repository
public class UserRoleRepository {

    private final DSLContext dsl;

    public UserRoleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public boolean isAdmin(long userId) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(USER_ROLE)
                        .where(USER_ROLE.USER_ID.eq(userId)
                                .and(USER_ROLE.ROLE.eq("admin")))
        );
    }
}
