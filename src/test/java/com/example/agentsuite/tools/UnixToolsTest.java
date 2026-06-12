package com.example.agentsuite.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UnixToolsTest {

    @TempDir
    Path root;

    @Test
    void cat_fileWithinRoot_returnsContent() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hi there");
        UnixTools tools = new UnixTools(root.toString());
        assertThat(tools.cat("hello.txt")).contains("hi there");
    }

    @Test
    void cat_absolutePathOutsideRoot_blocked(@TempDir Path other) throws Exception {
        Path secret = other.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-VALUE");
        UnixTools tools = new UnixTools(root.toString());
        String result = tools.cat(secret.toString());
        assertThat(result).doesNotContain("TOP-SECRET-VALUE");
        assertThat(result).contains("outside the root");
    }

    @Test
    void cat_parentTraversal_blocked() {
        UnixTools tools = new UnixTools(root.toString());
        assertThat(tools.cat("../../etc/passwd")).contains("outside the root");
    }

    @Test
    void ls_absolutePathOutsideRoot_blocked(@TempDir Path other) {
        UnixTools tools = new UnixTools(root.toString());
        assertThat(tools.ls(other.toString())).contains("outside the root");
    }

    @Test
    void ls_dotListsRoot() throws Exception {
        Files.writeString(root.resolve("a.txt"), "x");
        UnixTools tools = new UnixTools(root.toString());
        assertThat(tools.ls(".")).contains("a.txt");
    }
}
