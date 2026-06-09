package com.example.agentsuite.jooq;

import com.example.agentsuite.jooq.repository.SuiteUserRepository;
import com.example.agentsuite.jooq.repository.UserRoleRepository;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@JooqTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SuiteUserRepository.class, UserRoleRepository.class})
@TestPropertySource(properties = "spring.sql.init.mode=always")
@Transactional
class UserRoleRepositoryTest {

    @Autowired SuiteUserRepository suiteUserRepo;
    @Autowired UserRoleRepository userRoleRepo;
    @Autowired DSLContext dsl;

    private long userId;

    @BeforeEach
    void setUp() {
        userId = suiteUserRepo.insert("test-uuid", "test@example.com");
    }

    @Test
    void isAdmin_noRoleRow_returnsFalse() {
        assertThat(userRoleRepo.isAdmin(userId)).isFalse();
    }

    @Test
    void isAdmin_adminRoleRow_returnsTrue() {
        dsl.insertInto(DSL.table("user_role"))
                .set(DSL.field("user_id", Long.class), userId)
                .set(DSL.field("role", String.class), "admin")
                .execute();
        assertThat(userRoleRepo.isAdmin(userId)).isTrue();
    }

    @Test
    void isAdmin_otherUserIsAdmin_returnsFalse() {
        long otherId = suiteUserRepo.insert("other-uuid", "other@example.com");
        dsl.insertInto(DSL.table("user_role"))
                .set(DSL.field("user_id", Long.class), otherId)
                .set(DSL.field("role", String.class), "admin")
                .execute();
        assertThat(userRoleRepo.isAdmin(userId)).isFalse();
    }
}
