package com.example.agentsuite.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unix4j.Unix4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class UnixTools {

    private static final Logger log = LoggerFactory.getLogger(UnixTools.class);

    public final Path root;
    private Set<String> gitTrackedCache;
    private boolean gitCacheLoaded;

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

        String raw = Unix4j.cd(root.toString()).ls(relativePath).toStringResult();

        if (!Files.exists(root.resolve(".git"))) {
            return raw;
        }

        String prefix = relativePath.equals(".") || relativePath.equals("./") ? "" : relativePath.replace('\\', '/') + "/";

        List<String> entries = raw.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (entries.isEmpty()) {
            return raw;
        }

        Set<String> candidates = new LinkedHashSet<>();
        for (String entry : entries) {
            candidates.add(normalize(prefix + entry));
        }

        Set<String> ignored = checkIgnored(candidates);

        StringBuilder result = new StringBuilder();
        for (String entry : entries) {
            String fullPath = normalize(prefix + entry);
            if (!ignored.contains(fullPath)) {
                result.append(entry).append("\n");
            }
        }

        return result.isEmpty() ? raw : result.toString().trim();
    }

    @Tool("Concatenate and display the content of the specified file relative to the root directory")
    public String cat(@P("Relative path to the file") String relativePath) {
        log.info("cat {}", relativePath);
        if (relativePath.contains("..")) {
            return "Error: Access to parent directories is not allowed.";
        }
        if (Files.exists(root.resolve(".git")) && !isPathAllowed(normalize(relativePath))) {
            return "Error: File is git-ignored: " + relativePath;
        }
        return Unix4j.cd(root.toString()).cat(relativePath).toStringResult();
    }

    @Tool("Recursively search for the specified text in files under the root directory")
    public String grep(@P("Text to search for") String searchText, @P("File filter (e.g. *.txt), use \"*\" for all files") String fileFilter) {
        log.info("grep {} {}", searchText, fileFilter);
        if (fileFilter.isEmpty()) {
            fileFilter = "*";
        }
        String raw = Unix4j.cd(root.toString()).find(".", fileFilter).grep(searchText).toStringResult();

        if (!Files.exists(root.resolve(".git"))) {
            return raw;
        }

        Set<String> gitFiles = gitTrackedFiles();
        if (gitFiles.isEmpty()) {
            return raw;
        }

        StringBuilder result = new StringBuilder();
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String filePath = normalizePath(line);
            if (!gitFiles.contains(filePath)) {
                continue;
            }
            result.append(filePath).append("\n");
        }

        return result.isEmpty() ? raw : result.toString().trim();
    }

    private Set<String> gitTrackedFiles() {
        if (gitCacheLoaded) {
            return gitTrackedCache;
        }
        gitCacheLoaded = true;
        gitTrackedCache = new HashSet<>();
        try {
            ProcessRunner.Output result = new ProcessRunner(new String[]{
                    "git", "-C", root.toString(),
                    "ls-files", "--cached", "--others", "--exclude-standard"}).run();
            if (result.exitCode() != 0) {
                log.warn("git ls-files exited with code {}", result.exitCode());
                return gitTrackedCache;
            }
            for (String line : result.stdOut().split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    gitTrackedCache.add(normalize(trimmed));
                }
            }
        } catch (Exception e) {
            log.warn("git ls-files failed, skipping filtering", e);
        }
        return gitTrackedCache;
    }

    private boolean isPathAllowed(String path) {
        Set<String> gitFiles = gitTrackedFiles();
        if (gitFiles.isEmpty()) {
            return true;
        }
        if (gitFiles.contains(path)) {
            return true;
        }
        for (String gf : gitFiles) {
            if (gf.startsWith(path + "/")) {
                return true;
            }
        }
        return false;
    }

    private Set<String> checkIgnored(Set<String> paths) {
        Set<String> ignored = new HashSet<>();
        try {
            List<String> cmd = new ArrayList<>(List.of("git", "-C", root.toString(), "check-ignore"));
            cmd.addAll(paths);
            ProcessRunner.Output result = new ProcessRunner(cmd.toArray(String[]::new)).run();

            if (result.exitCode() == 1) {
                return ignored;
            }
            if (result.exitCode() != 0) {
                log.warn("git check-ignore exited with code {}", result.exitCode());
                return ignored;
            }

            for (String line : result.stdOut().split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    ignored.add(normalize(trimmed));
                }
            }
        } catch (Exception e) {
            log.warn("git check-ignore failed, skipping filtering", e);
        }
        return ignored;
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    private static String normalizePath(String path) {
        String p = path.replace('\\', '/');
        if (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p;
    }
}
