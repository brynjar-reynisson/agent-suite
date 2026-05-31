package com.example.agentsuite.controller;

import com.example.agentsuite.service.ChatEvent;
import com.example.agentsuite.service.ChatOrchestrationService;
import com.example.agentsuite.service.ModelRegistry;
import com.example.agentsuite.tools.MarkDownWriter;
import com.example.agentsuite.tools.UnixTools;
import com.example.agentsuite.tools.WebTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.file.Path;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import java.util.function.Consumer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AiController.class)
class AiControllerTest {

    @TempDir
    static Path tempDir;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatOrchestrationService orchestrationService;

    @MockBean
    private ModelRegistry modelRegistry;

    @Test
    void chat_unknownModel_returnsError() throws Exception {
        // Model validation now happens inside ChatOrchestrationService; simulate it sending an error event.
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(5);
            consumer.accept(new ChatEvent.Error("Unknown model: gpt-4o"));
            consumer.accept(new ChatEvent.Done());
            return null;
        }).when(orchestrationService).chatStream(isNull(), eq("gpt-4o"), any(), any(), any(),
                any(Consumer.class), any());

        MvcResult mvcResult = mockMvc.perform(get("/ai/chat").param("model", "gpt-4o"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("Unknown model: gpt-4o")));
    }

    @Test
    void chat_disallowedDirectory_returnsError() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/ai/chat").param("rootDirectory", "/etc/passwd"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("Access to the specified root directory is not allowed")));
    }

    @Test
    void directories_returnsAllowedDirectories() throws Exception {
        mockMvc.perform(get("/ai/config/directories"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("C:/Users/Lenovo/misc_projects/dragon")));
    }

    @Test
    void tools_emptyRootDirectory_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "ls src")
                        .param("rootDirectory", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("Select a root directory")));
    }

    @Test
    void tools_disallowedDirectory_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "ls src")
                        .param("rootDirectory", "/etc/passwd"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("Access to the specified root directory is not allowed")));
    }

    @Test
    void tools_unknownCommand_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "rm -rf /")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("Unknown command")));
    }

    @Test
    void tools_emptyCommand_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("No command specified")));
    }

    @Test
    void tools_gitStatus_returnsGitOutput() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git status")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.not(
                        org.hamcrest.CoreMatchers.containsString("Unknown command"))))
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("branch")));
    }

    @Test
    void tools_gitNoSubcommand_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("git requires a subcommand")));
    }

    @Test
    void tools_gitUnknownSubcommand_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git rebase")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("Unknown git subcommand")));
    }

    @Test
    void tools_gitAddMissingArg_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git add")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("add requires a file path")));
    }

    @Test
    void tools_gitCommitMissingMessage_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git commit")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("commit requires a message")));
    }

    @Test
    void tools_gitNewBranchMissingArg_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git newBranch")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("newBranch requires a branch name")));
    }

    @Test
    void tools_gitCheckoutBranchMissingArg_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git checkoutBranch")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("checkoutBranch requires a branch name")));
    }

    @Test
    void tools_gitPull_doesNotReturnUnknownSubcommand() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git pull")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.not(
                        org.hamcrest.CoreMatchers.containsString("Unknown git subcommand"))));
    }

    @Test
    void buildToolInstances_emptyTools_returnsEmptyArray() {
        Object[] result = AiController.buildToolInstances("", tempDir.toString(), "");
        assertThat(result).isEmpty();
    }

    @Test
    void buildToolInstances_unixGroup_noRootDirectory_returnsEmptyArray() {
        Object[] result = AiController.buildToolInstances("unix", "", "");
        assertThat(result).isEmpty();
    }

    @Test
    void buildToolInstances_unixGroup_withRootDirectory_returnsUnixTools() {
        Object[] result = AiController.buildToolInstances("unix", tempDir.toString(), "");
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(UnixTools.class);
    }

    @Test
    void buildToolInstances_unknownGroup_silentlyIgnored() {
        Object[] result = AiController.buildToolInstances("unknown", tempDir.toString(), "");
        assertThat(result).isEmpty();
    }

    @Test
    void buildToolInstances_blankTools_returnsEmptyArray() {
        Object[] result = AiController.buildToolInstances("  ", tempDir.toString(), "");
        assertThat(result).isEmpty();
    }

    @Test
    void buildToolInstances_multipleGroups_onlyKnownGroupsAdded() {
        Object[] result = AiController.buildToolInstances("unix,unknown", tempDir.toString(), "");
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(UnixTools.class);
    }

    @Test
    void buildToolInstances_mdWriterGroup_withRootDirectory_returnsMarkDownWriter() {
        Object[] result = AiController.buildToolInstances("md-writer", tempDir.toString(), "");
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(MarkDownWriter.class);
    }

    @Test
    void buildToolInstances_mdWriterGroup_noRootDirectory_returnsEmptyArray() {
        Object[] result = AiController.buildToolInstances("md-writer", "", "");
        assertThat(result).isEmpty();
    }

    @Test
    void buildToolInstances_unixAndMdWriter_withRootDirectory_returnsBothInstances() {
        Object[] result = AiController.buildToolInstances("unix,md-writer", tempDir.toString(), "");
        assertThat(result).hasSize(2);
        assertThat(result[0]).isInstanceOf(UnixTools.class);
        assertThat(result[1]).isInstanceOf(MarkDownWriter.class);
    }

    @Test
    void buildToolInstances_mdWriterAndUnknown_onlyMarkDownWriterAdded() {
        Object[] result = AiController.buildToolInstances("md-writer,unknown", tempDir.toString(), "");
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(MarkDownWriter.class);
    }

    @Test
    void buildToolInstances_webGroup_returnsWebTools() {
        Object[] result = AiController.buildToolInstances("web", "", "");
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(WebTools.class);
    }

    @Test
    void chat_withUnixToolsAndValidRootDirectory_noErrorEvent() throws Exception {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(5);
            consumer.accept(new ChatEvent.Done());
            return null;
        }).when(orchestrationService).chatStream(isNull(), any(), any(), any(), any(),
                any(Consumer.class), any());

        MvcResult mvcResult = mockMvc.perform(get("/ai/chat")
                        .param("tools", "unix")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.not(
                        org.hamcrest.CoreMatchers.containsString("error"))));
    }
}
