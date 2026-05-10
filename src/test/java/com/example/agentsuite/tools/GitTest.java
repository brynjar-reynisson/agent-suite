package com.example.agentsuite.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitTest {

    @TempDir
    Path tempDir;
    private Git git;

    @BeforeEach
    void setUp() throws Exception {
        new ProcessRunner(new String[]{"git", "-C", tempDir.toString(), "init"}).run();
        new ProcessRunner(new String[]{"git", "-C", tempDir.toString(), "config", "user.email", "test@test.com"}).run();
        new ProcessRunner(new String[]{"git", "-C", tempDir.toString(), "config", "user.name", "Test"}).run();
        git = new Git(tempDir.toString());
    }

    @Test
    void status_returnsOutput() {
        String result = git.status();
        assertThat(result).doesNotStartWith("Error:").contains("branch");
    }

    @Test
    void add_stagedFile_returnsAddedMessage() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello");
        String result = git.add("hello.txt");
        assertThat(result).isEqualTo("Added hello.txt");
    }

    @Test
    void commit_withMessage_returnsCommitOutput() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello");
        git.add("hello.txt");
        String result = git.commit("initial commit");
        assertThat(result).contains("initial commit");
    }

    @Test
    void newBranch_returnsConfirmationMessage() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello");
        git.add("hello.txt");
        git.commit("initial commit");
        String result = git.newBranch("my-feature");
        assertThat(result).isEqualTo("Created and switched to branch my-feature");
    }

    @Test
    void checkoutBranch_returnsConfirmationMessage() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello");
        git.add("hello.txt");
        git.commit("initial commit");
        git.newBranch("branch-a");
        git.newBranch("branch-b");
        String result = git.checkoutBranch("branch-a");
        assertThat(result).isEqualTo("Switched to branch branch-a");
    }

    @Test
    void push_noRemote_returnsError() {
        String result = git.push();
        assertThat(result).startsWith("Error:");
    }
}
