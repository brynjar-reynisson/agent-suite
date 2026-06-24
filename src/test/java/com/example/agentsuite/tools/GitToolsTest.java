package com.example.agentsuite.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitToolsTest {

    @TempDir
    Path tempDir;
    private GitTools gitTools;

    @BeforeEach
    void setUp() throws Exception {
        new ProcessRunner(new String[]{"git", "-C", tempDir.toString(), "init"}).run();
        new ProcessRunner(new String[]{"git", "-C", tempDir.toString(), "config", "user.email", "test@test.com"}).run();
        new ProcessRunner(new String[]{"git", "-C", tempDir.toString(), "config", "user.name", "Test"}).run();
        gitTools = new GitTools(tempDir.toString());
    }

    @Test
    void gitAdd_newFile_returnsAddedMessage() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello");
        String result = gitTools.gitAdd("hello.txt");
        assertThat(result).isEqualTo("Added hello.txt");
    }

    @Test
    void gitAdd_nonexistentFile_returnsError() {
        String result = gitTools.gitAdd("missing.txt");
        assertThat(result).startsWith("Error:");
    }

    @Test
    void gitCommit_afterAdd_returnsCommitOutput() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello");
        gitTools.gitAdd("hello.txt");
        String result = gitTools.gitCommit("initial commit");
        assertThat(result).contains("initial commit");
    }

    @Test
    void gitCommit_nothingStaged_returnsError() {
        String result = gitTools.gitCommit("empty");
        assertThat(result).startsWith("Error:");
    }
}
