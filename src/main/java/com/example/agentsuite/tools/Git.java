package com.example.agentsuite.tools;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Git {

    private final Path root;

    public Git(String rootDirectory) {
        root = Paths.get(rootDirectory);
        if (!root.toFile().exists() || !root.toFile().isDirectory()) {
            throw new IllegalArgumentException("Root directory does not exist or is not a directory: " + rootDirectory);
        }
    }

    public String status() {
        ProcessRunner.Output output = new ProcessRunner(new String[]{"git", "-C", root.toString(), "status"}).run();
        if (output.exitCode() != 0) {
            return "Error: " + output.stdErr();
        }
        return output.stdOut();
    }

    public String add(String relativePath) {
        ProcessRunner runner = new ProcessRunner(new String[]{"git", "-C", root.toString(), "add", relativePath});
        ProcessRunner.Output output = runner.run();
        if (output.exitCode() != 0) {
            return "Error: " + output.stdErr();
        }
        return "Added " + relativePath;
    }

    public String commit(String message) {
        ProcessRunner runner = new ProcessRunner(new String[]{"git", "-C", root.toString(), "commit", "-m", message});
        ProcessRunner.Output output = runner.run();
        if (output.exitCode() != 0) {
            return "Error: " + output.stdErr();
        }
        return output.stdOut();
    }

    public String push() {
        ProcessRunner runner = new ProcessRunner(new String[]{"git", "-C", root.toString(), "push"});
        ProcessRunner.Output output = runner.run();
        if (output.exitCode() != 0) {
            return "Error: " + output.stdErr();
        }
        return output.stdOut();
    }

    public String pull() {
        ProcessRunner.Output output = new ProcessRunner(new String[]{"git", "-C", root.toString(), "pull"}).run();
        if (output.exitCode() != 0) {
            return "Error: " + output.stdErr();
        }
        return output.stdOut();
    }

    public String newBranch(String branchName) {
        ProcessRunner runner = new ProcessRunner(new String[]{"git", "-C", root.toString(), "checkout", "-b", branchName});
        ProcessRunner.Output output = runner.run();
        if (output.exitCode() != 0) {
            return "Error: " + output.stdErr();
        }
        return "Created and switched to branch " + branchName;
    }

    public String checkoutBranch(String branchName) {
        ProcessRunner runner = new ProcessRunner(new String[]{"git", "-C", root.toString(), "checkout", branchName});
        ProcessRunner.Output output = runner.run();
        if (output.exitCode() != 0) {
            return "Error: " + output.stdErr();
        }
        return "Switched to branch " + branchName;
    }
}
