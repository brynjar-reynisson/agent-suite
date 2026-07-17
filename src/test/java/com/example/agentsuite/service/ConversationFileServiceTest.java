package com.example.agentsuite.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationFileServiceTest {

    @TempDir
    Path tempDir;

    private ConversationFileService service;

    @BeforeEach
    void setUp() {
        service = new ConversationFileService(tempDir, "dev", true);
    }

    @Test
    void createFile_writesHeaderAndReturnsFileName() throws IOException {
        Optional<String> fileName = service.createFile(
                "breynisson@gmail.com", "Bug triage", "ext-1", OffsetDateTime.parse("2026-07-17T10:00:00Z"));

        assertThat(fileName).contains("breynisson_Bug triage-dev.md");
        String content = Files.readString(tempDir.resolve(fileName.get()));
        assertThat(content).contains("# Bug triage");
        assertThat(content).contains("- User: breynisson");
        assertThat(content).contains("- External ID: ext-1");
        assertThat(content).contains("- Environment: dev");
    }

    @Test
    void createFile_disabled_returnsEmptyAndWritesNothing() throws IOException {
        ConversationFileService disabled = new ConversationFileService(tempDir, null, false);

        Optional<String> fileName = disabled.createFile(
                "someone@example.com", "Chat", "ext-2", OffsetDateTime.now());

        assertThat(fileName).isEmpty();
        try (var stream = Files.list(tempDir)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void createFile_guestUser_usesGuestPrefix() {
        Optional<String> fileName = service.createFile(null, "Anon chat", "ext-3", OffsetDateTime.now());

        assertThat(fileName).contains("guest_Anon chat-dev.md");
    }

    @Test
    void createFile_collidingName_appendsSuffix() {
        Optional<String> first = service.createFile("a@x.com", "Same Name", "ext-4", OffsetDateTime.now());
        Optional<String> second = service.createFile("a@x.com", "Same Name", "ext-5", OffsetDateTime.now());

        assertThat(first).contains("a_Same Name-dev.md");
        assertThat(second).contains("a_Same Name-dev (2).md");
    }

    @Test
    void appendMessage_appendsBlocksInOrder() throws IOException {
        String fileName = service.createFile("a@x.com", "Chat", "ext-6", OffsetDateTime.now()).orElseThrow();

        service.appendMessage(fileName, "user", "Hello", OffsetDateTime.parse("2026-07-17T10:00:01Z"));
        service.appendMessage(fileName, "assistant", "Hi there", OffsetDateTime.parse("2026-07-17T10:00:02Z"));

        String content = Files.readString(tempDir.resolve(fileName));
        assertThat(content.indexOf("### user")).isLessThan(content.indexOf("### assistant"));
        assertThat(content).contains("Hello").contains("Hi there");
    }

    @Test
    void appendMessage_toolCallFencedAsJson() throws IOException {
        String fileName = service.createFile("a@x.com", "Chat", "ext-7", OffsetDateTime.now()).orElseThrow();

        service.appendMessage(fileName, "tool_call", "[{\"name\":\"ls\"}]", OffsetDateTime.now());

        String content = Files.readString(tempDir.resolve(fileName));
        assertThat(content).contains("```json\n[{\"name\":\"ls\"}]\n```");
    }

    @Test
    void appendMessage_toolResult_skipped() throws IOException {
        String fileName = service.createFile("a@x.com", "Chat", "ext-12", OffsetDateTime.now()).orElseThrow();
        long sizeBeforeAppend = Files.size(tempDir.resolve(fileName));

        service.appendMessage(fileName, "tool_result",
                "[{\"name\":\"cat\",\"result\":\"password=hunter2\"}]", OffsetDateTime.now());

        String content = Files.readString(tempDir.resolve(fileName));
        assertThat(content).doesNotContain("tool_result").doesNotContain("hunter2");
        assertThat(Files.size(tempDir.resolve(fileName))).isEqualTo(sizeBeforeAppend);
    }

    @Test
    void appendMessage_disabled_doesNothing() throws IOException {
        ConversationFileService disabled = new ConversationFileService(tempDir, null, false);

        disabled.appendMessage("nonexistent.md", "user", "Hello", OffsetDateTime.now());

        try (var stream = Files.list(tempDir)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void renameFile_movesFileAndReturnsNewName() {
        String fileName = service.createFile("a@x.com", "Old Name", "ext-8", OffsetDateTime.now()).orElseThrow();

        Optional<String> renamed = service.renameFile(fileName, "a@x.com", "New Name");

        assertThat(renamed).contains("a_New Name-dev.md");
        assertThat(Files.exists(tempDir.resolve(fileName))).isFalse();
        assertThat(Files.exists(tempDir.resolve(renamed.get()))).isTrue();
    }

    @Test
    void renameFile_sameResolvedName_isNoopAndFileStays() {
        String fileName = service.createFile("a@x.com", "Same", "ext-9", OffsetDateTime.now()).orElseThrow();

        Optional<String> renamed = service.renameFile(fileName, "a@x.com", "Same");

        assertThat(renamed).contains(fileName);
        assertThat(Files.exists(tempDir.resolve(fileName))).isTrue();
    }

    @Test
    void renameFile_collidesWithAnotherConversation_appendsSuffix() {
        String taken = service.createFile("a@x.com", "Target Name", "ext-10", OffsetDateTime.now()).orElseThrow();
        String toRename = service.createFile("a@x.com", "Original Name", "ext-11", OffsetDateTime.now()).orElseThrow();

        Optional<String> renamed = service.renameFile(toRename, "a@x.com", "Target Name");

        assertThat(renamed).contains("a_Target Name-dev (2).md");
        assertThat(Files.exists(tempDir.resolve(taken))).isTrue();
    }

    @Test
    void renameFile_disabled_returnsEmpty() {
        ConversationFileService disabled = new ConversationFileService(tempDir, null, false);

        Optional<String> renamed = disabled.renameFile("some.md", "a@x.com", "New Name");

        assertThat(renamed).isEmpty();
    }

    @Test
    void sanitize_stripsUnsafeCharactersAndTruncates() {
        String result = ConversationFileService.sanitize("a/b:c*d?e\"f<g>h|i");
        assertThat(result).isEqualTo("a_b_c_d_e_f_g_h_i");

        String longName = "x".repeat(200);
        assertThat(ConversationFileService.sanitize(longName)).hasSize(80);
    }

    @Test
    void emailUser_extractsLocalPart() {
        assertThat(ConversationFileService.emailUser("breynisson@gmail.com")).isEqualTo("breynisson");
        assertThat(ConversationFileService.emailUser(null)).isEqualTo("guest");
        assertThat(ConversationFileService.emailUser("")).isEqualTo("guest");
    }
}
