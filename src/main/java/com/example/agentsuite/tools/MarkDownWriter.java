package com.example.agentsuite.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MarkDownWriter {

    private static final Logger log = LoggerFactory.getLogger(MarkDownWriter.class);

    public final Path root;

    public MarkDownWriter(String rootDirectory) {
        root = Paths.get(rootDirectory);
        if (!root.toFile().exists() || !root.toFile().isDirectory()) {
            throw new IllegalArgumentException(
                    "Root directory does not exist or is not a directory: " + rootDirectory);
        }
    }

    @Tool("Create new markdown spec or plan file for a feature with the given content. Actual file name will be auto-generated and returned, based on the document type and current timestamp.")
    public String newMarkDownFile(
            @P("Document type, spec or plan") String documentType,
            @P("Feature name") String featureName,
            @P("The content to write") String content
    ) {
        Path docs = root.resolve("docs");
        Path mdFolder;
        if (documentType.equalsIgnoreCase("spec")) {
            mdFolder = docs.resolve("specs");
        } else if (documentType.equalsIgnoreCase("plan")) {
            mdFolder = docs.resolve("plans");
        } else {
            return "Error: Unknown document type: " + documentType + ". Use 'spec' or 'plan'.";
        }

        try {
            Files.createDirectories(mdFolder);
        } catch (Exception e) {
            return "Error: Could not create directory for markdown files: " + e.getMessage();
        }

        String safeFeatureName = featureName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        String fileName = String.format("%s-%s.md", java.time.LocalDate.now(), safeFeatureName);
        Path target = mdFolder.resolve(fileName);
        if (!target.startsWith(root) || fileName.contains("..")) {
            return "Error: Path escapes root directory.";
        }
        if (target.toFile().exists()) {
            return "Error: File already exists: " + target;
        }

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return "Successfully wrote " + target + " (" + content.length() + " bytes)";
        } catch (Exception e) {
            return "Error: Could not write file " + target + ", " + e.getMessage();
        }
    }
}
