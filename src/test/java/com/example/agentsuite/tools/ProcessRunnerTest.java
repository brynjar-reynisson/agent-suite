package com.example.agentsuite.tools;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessRunnerTest {

    @Test
    void run_capturesStdOut() {
        ProcessRunner.Output output = new ProcessRunner(new String[]{"git", "--version"}).run();
        assertThat(output.stdOut()).contains("git");
    }

    @Test
    void run_exitCodeZeroOnSuccess() {
        ProcessRunner.Output output = new ProcessRunner(new String[]{"git", "--version"}).run();
        assertThat(output.exitCode()).isEqualTo(0);
    }

    @Test
    void run_capturesStdErr() {
        ProcessRunner.Output output = new ProcessRunner(new String[]{"git", "not-a-real-subcommand"}).run();
        assertThat(output.stdErr()).isNotEmpty();
    }

    @Test
    void run_nonZeroExitCodeOnFailure() {
        ProcessRunner.Output output = new ProcessRunner(new String[]{"git", "not-a-real-subcommand"}).run();
        assertThat(output.exitCode()).isNotEqualTo(0);
    }

    @Test
    void run_nonExistentCommand_exitCodeNegativeOne() {
        ProcessRunner.Output output = new ProcessRunner(new String[]{"this-command-xyz-does-not-exist"}).run();
        assertThat(output.exitCode()).isEqualTo(-1);
    }

    @Test
    void run_nonExistentCommand_stdErrContainsMessage() {
        ProcessRunner.Output output = new ProcessRunner(new String[]{"this-command-xyz-does-not-exist"}).run();
        assertThat(output.stdErr()).isNotEmpty();
    }

    @Test
    void stdInLineWriter_writesLinesWithNewlines() throws Exception {
        var writer = new ProcessRunner.StdInLineWriter(List.of("line1", "line2"), true);
        var out = new ByteArrayOutputStream();
        writer.writeTo(out);
        assertThat(out.toString()).isEqualTo("line1\nline2\n");
    }

    @Test
    void stdInLineWriter_writesLinesWithoutNewlines() throws Exception {
        var writer = new ProcessRunner.StdInLineWriter(List.of("a", "b"), false);
        var out = new ByteArrayOutputStream();
        writer.writeTo(out);
        assertThat(out.toString()).isEqualTo("ab");
    }

    @Test
    void stdInLineWriter_isDone_falseBeforeWrite_trueAfter() throws Exception {
        var writer = new ProcessRunner.StdInLineWriter(List.of("test"), true);
        assertThat(writer.isDone()).isFalse();
        writer.writeTo(new ByteArrayOutputStream());
        assertThat(writer.isDone()).isTrue();
    }
}
