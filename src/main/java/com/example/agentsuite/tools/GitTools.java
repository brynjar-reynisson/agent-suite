package com.example.agentsuite.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitTools {

    private static final Logger log = LoggerFactory.getLogger(GitTools.class);

    private final Git git;

    public GitTools(String rootDirectory) {
        this.git = new Git(rootDirectory);
    }

    @Tool("Add a file to the git staging area. Always call this after creating or modifying a file.")
    public String gitAdd(@P("Relative path to the file to stage") String relativePath) {
        log.info("gitAdd {}", relativePath);
        return git.add(relativePath);
    }

    @Tool("Commit all staged changes with a message. Always call after gitAdd.")
    public String gitCommit(@P("Commit message describing the change") String message) {
        log.info("gitCommit {}", message);
        return git.commit(message);
    }
}
