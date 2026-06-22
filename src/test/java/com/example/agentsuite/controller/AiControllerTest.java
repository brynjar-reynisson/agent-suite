package com.example.agentsuite.controller;

import com.example.agentsuite.controller.ConversationDetailDto;
import com.example.agentsuite.controller.ConversationSummaryDto;
import com.example.agentsuite.jooq.service.ConversationService;
import com.example.agentsuite.jooq.service.SuiteUserService;
import com.example.agentsuite.service.AuthorizationService;
import com.example.agentsuite.service.ChatEvent;
import com.example.agentsuite.service.ChatOrchestrationService;
import com.example.agentsuite.service.ModelRegistry;
import com.example.agentsuite.tools.MarkDownWriter;
import com.example.agentsuite.tools.McpToolBridge;
import com.example.agentsuite.tools.UnixTools;
import com.example.agentsuite.tools.WebTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.file.Path;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.function.Consumer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @MockBean
    private ConversationService conversationService;

    @MockBean
    private SuiteUserService suiteUserService;

    @MockBean
    private AuthorizationService authorizationService;

    @MockBean
    private McpToolBridge mcpToolBridge;

    @BeforeEach
    void setUpAuth() {
        when(authorizationService.grantedToolGroups(false)).thenReturn(List.of("web"));
        when(authorizationService.grantedToolGroups(true)).thenReturn(List.of("web", "md-writer", "mcp", "audio"));
        // Admin JWT (sub=admin-sub) resolves to an admin user; used by git-tool tests below.
        lenient().when(suiteUserService.findOrCreate("admin-sub", "admin@test.com")).thenReturn(42L);
        lenient().when(authorizationService.isAdmin(42L)).thenReturn(true);
        lenient().when(mcpToolBridge.scopedProvider(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new McpToolBridge.ScopedTools(Map.of()));
    }

    private static final String ADMIN_BEARER = "Bearer " + makeAdminJwt("admin-sub", "admin@test.com");

    @Test
    void chat_unknownModel_returnsError() throws Exception {
        // Model validation now happens inside ChatOrchestrationService; simulate it sending an error event.
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(7);
            consumer.accept(new ChatEvent.Error("Unknown model: gpt-4o"));
            consumer.accept(new ChatEvent.Done());
            return null;
        }).when(orchestrationService).chatStream(isNull(), anyLong(), eq("gpt-4o"), any(), any(), any(), any(),
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
    void directories_includesObsidianVault() throws Exception {
        mockMvc.perform(get("/ai/config/directories"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString(
                        "C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian")));
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
                        .header("Authorization", ADMIN_BEARER)
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
                        .header("Authorization", ADMIN_BEARER)
                        .param("command", "git")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("git requires a subcommand")));
    }

    @Test
    void tools_gitUnknownSubcommand_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .header("Authorization", ADMIN_BEARER)
                        .param("command", "git rebase")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("Unknown git subcommand")));
    }

    @Test
    void tools_gitAddMissingArg_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .header("Authorization", ADMIN_BEARER)
                        .param("command", "git add")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("add requires a file path")));
    }

    @Test
    void tools_gitCommitMissingMessage_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .header("Authorization", ADMIN_BEARER)
                        .param("command", "git commit")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("commit requires a message")));
    }

    @Test
    void tools_gitNewBranchMissingArg_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .header("Authorization", ADMIN_BEARER)
                        .param("command", "git newBranch")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("newBranch requires a branch name")));
    }

    @Test
    void tools_gitCheckoutBranchMissingArg_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .header("Authorization", ADMIN_BEARER)
                        .param("command", "git checkoutBranch")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("checkoutBranch requires a branch name")));
    }

    @Test
    void tools_gitPull_doesNotReturnUnknownSubcommand() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .header("Authorization", ADMIN_BEARER)
                        .param("command", "git pull")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.not(
                        org.hamcrest.CoreMatchers.containsString("Unknown git subcommand"))));
    }

    @Test
    void tools_gitCommand_guestUser_returnsAuthorizationError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git push")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("Admin role required")));
    }

    @Test
    void mcpTools_guestUser_returnsForbidden() throws Exception {
        mockMvc.perform(get("/ai/config/mcp-tools"))
                .andExpect(status().isForbidden());
    }

    @Test
    void mcpTools_adminUser_returnsToolNames() throws Exception {
        when(mcpToolBridge.toolNames("")).thenReturn(List.of("mcp__server__tool"));
        mockMvc.perform(get("/ai/config/mcp-tools")
                        .header("Authorization", ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("mcp__server__tool"));
    }

    @Test
    void mcpTools_adminUser_withRootDirectory_returnsRootScopedNames() throws Exception {
        when(mcpToolBridge.toolNames("C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian"))
                .thenReturn(List.of("mcp__obsidian__read_note"));
        mockMvc.perform(get("/ai/config/mcp-tools")
                        .header("Authorization", ADMIN_BEARER)
                        .param("rootDirectory", "C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("mcp__obsidian__read_note"));
    }

    @Test
    void mcpTools_adminUser_disallowedRootDirectory_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/ai/config/mcp-tools")
                        .header("Authorization", ADMIN_BEARER)
                        .param("rootDirectory", "C:/not/allowed"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void buildToolInstances_emptyTools_returnsEmptyArray() {
        Object[] result = AiController.buildToolInstances("", tempDir.toString(), "", null, null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void buildToolInstances_unixGroup_noRootDirectory_returnsEmptyArray() {
        Object[] result = AiController.buildToolInstances("unix", "", "", null, null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void buildToolInstances_unixGroup_withRootDirectory_returnsUnixTools() {
        Object[] result = AiController.buildToolInstances("unix", tempDir.toString(), "", null, null, null);
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(UnixTools.class);
    }

    @Test
    void buildToolInstances_unknownGroup_silentlyIgnored() {
        Object[] result = AiController.buildToolInstances("unknown", tempDir.toString(), "", null, null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void buildToolInstances_blankTools_returnsEmptyArray() {
        Object[] result = AiController.buildToolInstances("  ", tempDir.toString(), "", null, null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void buildToolInstances_multipleGroups_onlyKnownGroupsAdded() {
        Object[] result = AiController.buildToolInstances("unix,unknown", tempDir.toString(), "", null, null, null);
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(UnixTools.class);
    }

    @Test
    void buildToolInstances_mdWriterGroup_withRootDirectory_returnsMarkDownWriter() {
        Object[] result = AiController.buildToolInstances("md-writer", tempDir.toString(), "", null, null, null);
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(MarkDownWriter.class);
    }

    @Test
    void buildToolInstances_mdWriterGroup_noRootDirectory_returnsEmptyArray() {
        Object[] result = AiController.buildToolInstances("md-writer", "", "", null, null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void buildToolInstances_unixAndMdWriter_withRootDirectory_returnsBothInstances() {
        Object[] result = AiController.buildToolInstances("unix,md-writer", tempDir.toString(), "", null, null, null);
        assertThat(result).hasSize(2);
        assertThat(result[0]).isInstanceOf(UnixTools.class);
        assertThat(result[1]).isInstanceOf(MarkDownWriter.class);
    }

    @Test
    void buildToolInstances_mdWriterAndUnknown_onlyMarkDownWriterAdded() {
        Object[] result = AiController.buildToolInstances("md-writer,unknown", tempDir.toString(), "", null, null, null);
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(MarkDownWriter.class);
    }

    @Test
    void buildToolInstances_webGroup_returnsWebTools() {
        Object[] result = AiController.buildToolInstances("web", "", "", null, null, null);
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(WebTools.class);
    }

    @Test
    void buildToolInstances_mcpGroup_noBridge_returnsEmpty() {
        Object[] result = AiController.buildToolInstances("mcp", "", "", null, null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void buildToolInstances_mcpGroup_withBridge_returnsRootScopedProvider() {
        when(mcpToolBridge.scopedProvider("C:/some/root"))
                .thenReturn(new McpToolBridge.ScopedTools(Map.of()));
        Object[] result = AiController.buildToolInstances("mcp", "C:/some/root", "", mcpToolBridge, null, null);
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(McpToolBridge.ScopedTools.class);
        verify(mcpToolBridge).scopedProvider("C:/some/root");
    }

    @Test
    void buildToolInstances_audioGroup_withParams_returnsAudioTools(@TempDir Path audioDir) {
        Object[] result = AiController.buildToolInstances(
                "audio", "", "", null, "http://localhost:8090", audioDir);
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(com.example.agentsuite.tools.AudioTools.class);
    }

    @Test
    void buildToolInstances_audioGroup_nullParams_returnsEmpty() {
        Object[] result = AiController.buildToolInstances("audio", "", "", null, null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void conversations_returnsSummaryList() throws Exception {
        when(conversationService.getConversationSummaries(anyLong())).thenReturn(List.of(
                new ConversationSummaryDto("ext-abc", "Hello world", null, "2026-06-01T10:00:00Z",
                        "deepseek-v4-pro", "")
        ));

        mockMvc.perform(get("/ai/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("ext-abc"))
                .andExpect(jsonPath("$[0].name").value("Hello world"))
                .andExpect(jsonPath("$[0].initialModel").value("deepseek-v4-pro"));
    }

    @Test
    void conversations_emptyList_returnsEmptyArray() throws Exception {
        when(conversationService.getConversationSummaries(anyLong())).thenReturn(List.of());

        mockMvc.perform(get("/ai/conversations"))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    void conversationDetail_knownId_returnsMessages() throws Exception {
        when(conversationService.getConversationDetail(eq("ext-abc"), anyLong())).thenReturn(
                new ConversationDetailDto("ext-abc", "Hello", null, "2026-06-01T10:00:00Z",
                        "deepseek-v4-pro", "", "",
                        List.of(new ConversationDetailDto.MessageDto("user", "Hi there", List.of())))
        );

        mockMvc.perform(get("/ai/conversations/ext-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("ext-abc"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("Hi there"));
    }

    @Test
    void conversationDetail_unknownId_returns404() throws Exception {
        when(conversationService.getConversationDetail(eq("unknown"), anyLong()))
                .thenThrow(new NoSuchElementException("not found"));

        mockMvc.perform(get("/ai/conversations/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void chat_withUnixToolsAndValidRootDirectory_noErrorEvent() throws Exception {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(7);
            consumer.accept(new ChatEvent.Done());
            return null;
        }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(), any(),
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

    @Test
    void chat_guestUser_noToolsParam_onlyWebToolPassedToOrchestration() throws Exception {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(7);
            consumer.accept(new ChatEvent.Done());
            return null;
        }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(), any(),
                any(Consumer.class), any());

        MvcResult mvcResult = mockMvc.perform(get("/ai/chat"))
                .andExpect(request().asyncStarted()).andReturn();

        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        verify(orchestrationService).chatStream(
                isNull(), anyLong(), any(), any(), any(), any(), any(), any(Consumer.class),
                argThat(arr -> arr instanceof Object[] t && t.length == 1 && t[0] instanceof WebTools));
    }

    @Test
    void chat_guestUserWithRootDirectory_webAndUnixPassedToOrchestration() throws Exception {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(7);
            consumer.accept(new ChatEvent.Done());
            return null;
        }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(), any(),
                any(Consumer.class), any());

        MvcResult mvcResult = mockMvc.perform(get("/ai/chat")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(request().asyncStarted()).andReturn();

        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        verify(orchestrationService).chatStream(
                isNull(), anyLong(), any(), any(), any(), any(), any(), any(Consumer.class),
                argThat(arr -> arr instanceof Object[] t && t.length == 2
                        && t[0] instanceof WebTools && t[1] instanceof UnixTools));
    }

    @Test
    void chat_guestUserRequestsMdWriter_mdWriterStrippedServerSide() throws Exception {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(7);
            consumer.accept(new ChatEvent.Done());
            return null;
        }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(), any(),
                any(Consumer.class), any());

        MvcResult mvcResult = mockMvc.perform(get("/ai/chat")
                        .param("tools", "web,md-writer"))
                .andExpect(request().asyncStarted()).andReturn();

        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        verify(orchestrationService).chatStream(
                isNull(), anyLong(), any(), any(), any(), any(), any(), any(Consumer.class),
                argThat(arr -> arr instanceof Object[] t && t.length == 1 && t[0] instanceof WebTools));
    }

    @Test
    void chat_guestUserRequestsUnixOnly_noRootDirectory_emptyToolArray() throws Exception {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(7);
            consumer.accept(new ChatEvent.Done());
            return null;
        }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(), any(),
                any(Consumer.class), any());

        MvcResult mvcResult = mockMvc.perform(get("/ai/chat")
                        .param("tools", "unix"))
                .andExpect(request().asyncStarted()).andReturn();

        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        verify(orchestrationService).chatStream(
                isNull(), anyLong(), any(), any(), any(), any(), any(), any(Consumer.class),
                argThat(arr -> arr instanceof Object[] t && t.length == 0));
    }

    @Test
    void chat_adminUserNoRootDirectory_mdWriterStrippedServerSide() throws Exception {
        when(suiteUserService.findOrCreate("admin-sub", "admin@test.com")).thenReturn(42L);
        when(authorizationService.isAdmin(42L)).thenReturn(true);

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(7);
            consumer.accept(new ChatEvent.Done());
            return null;
        }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(), any(),
                any(Consumer.class), any());

        MvcResult mvcResult = mockMvc.perform(get("/ai/chat")
                        .header("Authorization", "Bearer " + makeAdminJwt("admin-sub", "admin@test.com")))
                .andExpect(request().asyncStarted()).andReturn();

        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        verify(orchestrationService).chatStream(
                isNull(), anyLong(), any(), any(), any(), any(), any(), any(Consumer.class),
                argThat(arr -> arr instanceof Object[] t && t.length == 3
                        && t[0] instanceof WebTools && t[1] instanceof McpToolBridge.ScopedTools
                        && t[2] instanceof com.example.agentsuite.tools.AudioTools));
    }

    @Test
    void chat_adminUserWithRootDirectory_allThreeToolsPassedToOrchestration() throws Exception {
        when(suiteUserService.findOrCreate("admin-sub", "admin@test.com")).thenReturn(42L);
        when(authorizationService.isAdmin(42L)).thenReturn(true);

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(7);
            consumer.accept(new ChatEvent.Done());
            return null;
        }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(), any(),
                any(Consumer.class), any());

        MvcResult mvcResult = mockMvc.perform(get("/ai/chat")
                        .header("Authorization", "Bearer " + makeAdminJwt("admin-sub", "admin@test.com"))
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(request().asyncStarted()).andReturn();

        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        // order is a deliberate contract: grantedToolGroups order (web, md-writer, mcp, audio) then unix last
        verify(orchestrationService).chatStream(
                isNull(), anyLong(), any(), any(), any(), any(), any(), any(Consumer.class),
                argThat(arr -> arr instanceof Object[] t && t.length == 5
                        && t[0] instanceof WebTools
                        && t[1] instanceof MarkDownWriter
                        && t[2] instanceof McpToolBridge.ScopedTools
                        && t[3] instanceof com.example.agentsuite.tools.AudioTools
                        && t[4] instanceof UnixTools));
    }

    @Test
    void userConfig_guestUser_returnsIsAdminFalseWithWebTool() throws Exception {
        mockMvc.perform(get("/ai/config/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAdmin").value(false))
                .andExpect(jsonPath("$.grantedToolGroups").isArray())
                .andExpect(jsonPath("$.grantedToolGroups[0]").value("web"))
                .andExpect(jsonPath("$.grantedToolGroups.length()").value(1));
    }

    @Test
    void chat_adminOptOutMdWriter_onlyWebAndUnixPassedToOrchestration() throws Exception {
        when(suiteUserService.findOrCreate("admin-sub", "admin@test.com")).thenReturn(42L);
        when(authorizationService.isAdmin(42L)).thenReturn(true);

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(7);
            consumer.accept(new ChatEvent.Done());
            return null;
        }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(), any(),
                any(Consumer.class), any());

        MvcResult mvcResult = mockMvc.perform(get("/ai/chat")
                        .header("Authorization", "Bearer " + makeAdminJwt("admin-sub", "admin@test.com"))
                        .param("tools", "web,unix")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(request().asyncStarted()).andReturn();

        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        verify(orchestrationService).chatStream(
                isNull(), anyLong(), any(), any(), any(), any(), any(), any(Consumer.class),
                argThat(arr -> arr instanceof Object[] t && t.length == 2
                        && t[0] instanceof WebTools && t[1] instanceof UnixTools));
    }

    @Test
    void compact_validRequest_returns200WithSummary() throws Exception {
        when(orchestrationService.compact(eq("conv-123"), anyLong()))
                .thenReturn("This is the summary.");

        mockMvc.perform(post("/ai/conversations/conv-123/compact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("This is the summary."));
    }

    @Test
    void compact_conversationNotFound_returns404() throws Exception {
        when(orchestrationService.compact(eq("unknown"), anyLong()))
                .thenThrow(new java.util.NoSuchElementException("Conversation not found"));

        mockMvc.perform(post("/ai/conversations/unknown/compact"))
                .andExpect(status().isNotFound());
    }

    @Test
    void compact_nothingToCompact_returns400WithError() throws Exception {
        when(orchestrationService.compact(eq("empty-conv"), anyLong()))
                .thenThrow(new IllegalArgumentException("Nothing to compact."));

        mockMvc.perform(post("/ai/conversations/empty-conv/compact"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Nothing to compact."));
    }

    private static String makeAdminJwt(String sub, String email) {
        return Jwts.builder()
                .setSubject(sub)
                .claim("email", email)
                .setIssuer("http://127.0.0.1:54321/auth/v1")
                .setAudience("authenticated")
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(
                        "test-secret-padded-to-at-least-32-characters".getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();
    }
}
