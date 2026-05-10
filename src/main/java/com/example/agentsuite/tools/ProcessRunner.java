package com.example.agentsuite.tools;

import java.util.Collection;

public class ProcessRunner {

    private final String[] args;

    public ProcessRunner(String[] args) {
        this.args = args;
    }

    public Output run() {
        int exitCode = -1;
        try {
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.redirectErrorStream(false);
            Process process = builder.start();
            String stdOut = new String(process.getInputStream().readAllBytes());
            String stdErr = new String(process.getErrorStream().readAllBytes());
            return new Output(stdOut, stdErr, process.waitFor());
        } catch (Exception e) {
            return new Output("", e.getMessage(), exitCode);
        }
    }

    interface StdInWriter {
        boolean isDone();
        void writeTo(java.io.OutputStream os) throws java.io.IOException;
    }

    public static final class StdInLineWriter implements StdInWriter {

        private final Collection<String> lines;
        private final boolean appendNewline;
        private boolean hasWritten = false;

        public StdInLineWriter(Collection<String> lines, boolean appendNewline) {
            this.lines = lines;
            this.appendNewline = appendNewline;
        }

        @Override
        public boolean isDone() {
            return hasWritten;
        }

        @Override
        public void writeTo(java.io.OutputStream os) throws java.io.IOException {
            for (String line : lines) {
                os.write(line.getBytes());
                if (appendNewline) {
                    os.write('\n');
                }
            }
            os.flush();
            hasWritten = true;
        }
    }

    public record Output(String stdOut, String stdErr, int exitCode) {
    }
}
