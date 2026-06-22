package com.example.agentsuite.jooq.repository;

import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.agentsuite.jooq.generated.Tables.MESSAGE;

@Repository
public class MessageRepository {

    private final DSLContext dsl;

    public MessageRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(long conversationId, long userId, String type, String message) {
        dsl.insertInto(MESSAGE)
                .set(MESSAGE.CONVERSATION_ID, conversationId)
                .set(MESSAGE.USER_ID, userId)
                .set(MESSAGE.TYPE, type)
                .set(MESSAGE.MESSAGE_, message)
                .execute();
    }

    public List<MessageRecord> findByConversationId(long conversationId) {
        return dsl.selectFrom(MESSAGE)
                .where(MESSAGE.CONVERSATION_ID.eq(conversationId))
                .orderBy(MESSAGE.MESSAGE_TIME.asc(), MESSAGE.MESSAGE_ID.asc())
                .fetch();
    }

    public Optional<String> findLastModelChange(long conversationId) {
        return dsl.select(MESSAGE.MESSAGE_)
                .from(MESSAGE)
                .where(MESSAGE.CONVERSATION_ID.eq(conversationId))
                .and(MESSAGE.TYPE.eq("model_change"))
                .orderBy(MESSAGE.MESSAGE_TIME.desc(), MESSAGE.MESSAGE_ID.desc())
                .limit(1)
                .fetchOptional(MESSAGE.MESSAGE_);
    }

    public Optional<String> findLastSystemPrompt(long conversationId) {
        return dsl.select(MESSAGE.MESSAGE_)
                .from(MESSAGE)
                .where(MESSAGE.CONVERSATION_ID.eq(conversationId))
                .and(MESSAGE.TYPE.eq("system_prompt"))
                .orderBy(MESSAGE.MESSAGE_TIME.desc(), MESSAGE.MESSAGE_ID.desc())
                .limit(1)
                .fetchOptional(MESSAGE.MESSAGE_);
    }

    public Map<Long, String[]> findFirstMetaByConversationIds(List<Long> conversationIds) {
        if (conversationIds.isEmpty()) return Map.of();
        Map<Long, String[]> result = new HashMap<>();
        dsl.select(MESSAGE.CONVERSATION_ID, MESSAGE.TYPE, MESSAGE.MESSAGE_)
                .from(MESSAGE)
                .where(MESSAGE.CONVERSATION_ID.in(conversationIds))
                .and(MESSAGE.TYPE.in("model_change", "system_prompt"))
                .orderBy(MESSAGE.MESSAGE_TIME.asc(), MESSAGE.MESSAGE_ID.asc())
                .forEach(r -> {
                    long convId = r.value1();
                    String type = r.value2();
                    String msg = r.value3();
                    result.computeIfAbsent(convId, k -> new String[]{"", ""});
                    String[] arr = result.get(convId);
                    if ("model_change".equals(type) && arr[0].isEmpty()) arr[0] = msg;
                    else if ("system_prompt".equals(type) && arr[1].isEmpty()) arr[1] = msg;
                });
        return result;
    }
}
