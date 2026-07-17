package com.example.agentsuite.service;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.generated.tables.records.SuiteUserRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import com.example.agentsuite.jooq.repository.SuiteUserRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.example.agentsuite.jooq.generated.Tables.CONVERSATION;
import static org.assertj.core.api.Assertions.assertThat;

@JooqTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ConversationRepository.class, MessageRepository.class, SuiteUserRepository.class})
@org.springframework.test.annotation.Commit
class ConversationFileBacksweepRunner {

    private static final String DEFAULT_DB_URL = "jdbc:postgresql://127.0.0.1:54322/postgres";
    private static final String DEFAULT_ENV_LABEL = "dev";

    @Autowired DSLContext dsl;
    @Autowired ConversationRepository conversationRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired SuiteUserRepository suiteUserRepository;

    @DynamicPropertySource
    static void backsweepDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                System.getenv().getOrDefault("BACKSWEEP_DB_URL", DEFAULT_DB_URL));
        registry.add("spring.datasource.username", () ->
                System.getenv().getOrDefault("BACKSWEEP_DB_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () ->
                System.getenv().getOrDefault("BACKSWEEP_DB_PASSWORD", "postgres"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    // Guards against the operator setting BACKSWEEP_DB_URL to a remote (e.g. prod) host while
    // forgetting to also override BACKSWEEP_ENV_LABEL away from its "dev" default -- which would
    // silently write real remote conversation content into the local conversations/dev/ tree.
    static void requireEnvLabelMatchesTarget(String dbUrl, String envLabel) {
        boolean looksLocal = dbUrl.contains("127.0.0.1") || dbUrl.contains("localhost");
        if (!looksLocal && DEFAULT_ENV_LABEL.equals(envLabel)) {
            throw new IllegalStateException("BACKSWEEP_DB_URL (" + dbUrl + ") does not look like the local dev "
                    + "database, but BACKSWEEP_ENV_LABEL is still \"dev\" (the default). Set "
                    + "BACKSWEEP_ENV_LABEL=prod explicitly to confirm this is intentional.");
        }
    }

    @Test
    void backfillMissingConversationFiles() {
        String dbUrl = System.getenv().getOrDefault("BACKSWEEP_DB_URL", DEFAULT_DB_URL);
        String envLabel = System.getenv().getOrDefault("BACKSWEEP_ENV_LABEL", DEFAULT_ENV_LABEL);
        requireEnvLabelMatchesTarget(dbUrl, envLabel);

        ConversationFileService fileService = new ConversationFileService(Path.of("conversations"), envLabel, true);

        List<ConversationRecord> pending = dsl.selectFrom(CONVERSATION)
                .where(CONVERSATION.MD_FILE_NAME.isNull())
                .orderBy(CONVERSATION.CONVERSATION_ID.asc())
                .fetch();

        System.out.println("=== Conversation file backsweep ===");
        System.out.println("Environment label: " + envLabel);
        System.out.println("Conversations missing md_file_name: " + pending.size());

        List<String> failures = new ArrayList<>();
        int succeeded = 0;

        for (ConversationRecord conv : pending) {
            String externalId = conv.getExternalId();
            try {
                String email = suiteUserRepository.findById(conv.getUserId())
                        .map(SuiteUserRecord::getEmail).orElse(null);
                String displayName = (conv.getCustomName() != null && !conv.getCustomName().isBlank())
                        ? conv.getCustomName() : conv.getConversationName();

                Optional<String> fileNameOpt = fileService.createFile(
                        email, displayName, externalId, conv.getCreateTime());
                if (fileNameOpt.isEmpty()) {
                    failures.add(externalId + ": createFile returned empty");
                    continue;
                }
                String fileName = fileNameOpt.get();

                List<MessageRecord> messages = messageRepository.findByConversationId(conv.getConversationId());
                for (MessageRecord msg : messages) {
                    fileService.appendMessage(fileName, msg.getType(), msg.getMessage(), msg.getMessageTime());
                }

                conversationRepository.updateMdFileName(conv.getConversationId(), fileName);
                System.out.println("  [" + externalId + "] -> " + fileName + " (" + messages.size() + " messages)");
                succeeded++;
            } catch (Exception e) {
                failures.add(externalId + ": " + e.getMessage());
            }
        }

        System.out.println("Succeeded: " + succeeded);
        System.out.println("Failed: " + failures.size());
        failures.forEach(f -> System.out.println("  " + f));
        System.out.println("=== Done ===");

        assertThat(failures).as("backsweep failures: %s", failures).isEmpty();
    }
}
