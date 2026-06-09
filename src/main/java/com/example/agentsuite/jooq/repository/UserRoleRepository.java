package com.example.agentsuite.jooq.repository;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class UserRoleRepository {

    private final DSLContext dsl;

    public UserRoleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public boolean isAdmin(long userId) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(DSL.table(DSL.name("user_role")))
                        .where(DSL.field(DSL.name("user_id"), Long.class).eq(userId)
                                .and(DSL.field(DSL.name("role"), String.class).eq("admin")))
        );
    }
}
