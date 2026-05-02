package com.example.agentsuite.tools;

import com.example.agentsuite.service.DeepSeekService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unix4j.Unix4j;

import java.nio.file.Path;
import java.nio.file.Paths;

public class UnixTools {

    private static final Logger log = LoggerFactory.getLogger(UnixTools.class);

    public final Path root;

    public UnixTools(String rootDirectory) {
        root = Paths.get(rootDirectory);
        if (!root.toFile().exists() || !root.toFile().isDirectory()) {
            throw new IllegalArgumentException("Root directory does not exist or is not a directory: " + rootDirectory);
        }
    }

    @Tool("List directories and files in the specified path relative to the root directory")
    public String ls(@P("Relative path to list, use \".\" for current directory") String relativePath) {
        log.info("ls {}", relativePath);
        if (relativePath.contains("..")) {
            return "Error: Access to parent directories is not allowed.";
        }
        return Unix4j.cd(root.toString()).ls(relativePath).toStringResult();
    }

    @Tool("Concatenate and display the content of the specified file relative to the root directory")
    public String cat(@P("Relative path to the file") String relativePath) {
        log.info("cat {}", relativePath);
        if (relativePath.contains("..")) {
            return "Error: Access to parent directories is not allowed.";
        }
        return Unix4j.cd(root.toString()).cat(relativePath).toStringResult();
    }
}
