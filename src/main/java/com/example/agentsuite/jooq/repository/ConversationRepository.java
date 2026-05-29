package com.example.agentsuite.jooq.repository;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.example.agentsuite.jooq.generated.Tables.CONVERSATION;

@Repository
public class ConversationRepository {

    private final DSLContext dsl;

    public ConversationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long insert(long userId, String name, String model, String rootDirectory) {
        return dsl.insertInto(CONVERSATION)
                .set(CONVERSATION.USER_ID, userId)
                .set(CONVERSATION.CONVERSATION_NAME, name)
                .set(CONVERSATION.MODEL, model)
                .set(CONVERSATION.ROOT_DIRECTORY, rootDirectory)
                .returning(CONVERSATION.CONVERSATION_ID)
                .fetchOne()
                .getConversationId();
    }

    public Optional<ConversationRecord> findById(long conversationId) {
        return dsl.selectFrom(CONVERSATION)
                .where(CONVERSATION.CONVERSATION_ID.eq(conversationId))
                .fetchOptional();
    }

    public List<ConversationRecord> findByUserId(long userId) {
        return dsl.selectFrom(CONVERSATION)
                .where(CONVERSATION.USER_ID.eq(userId))
                .orderBy(CONVERSATION.CREATE_TIME.desc())
                .fetch();
    }
}
