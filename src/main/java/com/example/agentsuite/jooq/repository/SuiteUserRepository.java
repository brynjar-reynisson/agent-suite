package com.example.agentsuite.jooq.repository;

import com.example.agentsuite.jooq.generated.tables.records.SuiteUserRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.example.agentsuite.jooq.generated.Tables.SUITE_USER;

@Repository
public class SuiteUserRepository {

    private final DSLContext dsl;

    public SuiteUserRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<SuiteUserRecord> findByUuid(String uuid) {
        return dsl.selectFrom(SUITE_USER)
                .where(SUITE_USER.UUID.eq(uuid))
                .fetchOptional();
    }

    public long insert(String uuid, String email) {
        return dsl.insertInto(SUITE_USER)
                .set(SUITE_USER.UUID, uuid)
                .set(SUITE_USER.EMAIL, email)
                .returning(SUITE_USER.USER_ID)
                .fetchSingle()
                .getUserId();
    }
}
