package com.example.agentsuite.jooq;

import com.example.agentsuite.jooq.repository.SuiteUserRepository;
import com.example.agentsuite.jooq.service.SuiteUserService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static com.example.agentsuite.jooq.generated.Tables.SUITE_USER;
import static org.assertj.core.api.Assertions.assertThat;

@JooqTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SuiteUserRepository.class)
@Transactional
@TestPropertySource(properties = "spring.sql.init.mode=always")
class SuiteUserServiceTest {

    @Autowired SuiteUserRepository suiteUserRepo;
    @Autowired DSLContext dsl;

    private SuiteUserService service;

    @BeforeEach
    void setUp() {
        service = new SuiteUserService(suiteUserRepo);
    }

    @Test
    void findOrCreate_createsNewUserForNewUuid() {
        long userId = service.findOrCreate("new-supabase-uuid-123", "new@example.com");
        assertThat(userId).isPositive();
        assertThat(dsl.selectCount().from(SUITE_USER)
                .where(SUITE_USER.UUID.eq("new-supabase-uuid-123"))
                .fetchOne(0, Integer.class)).isEqualTo(1);
    }

    @Test
    void findOrCreate_returnsSameIdOnSubsequentCalls() {
        long first  = service.findOrCreate("repeat-uuid-456", "repeat@example.com");
        long second = service.findOrCreate("repeat-uuid-456", "repeat@example.com");
        assertThat(first).isEqualTo(second);
    }
}
