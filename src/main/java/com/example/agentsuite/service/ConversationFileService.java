package com.example.agentsuite.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class ConversationFileService {

    private static final Logger log = LoggerFactory.getLogger(ConversationFileService.class);
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final int MAX_NAME_LENGTH = 80;

    private final Path baseDir;
    private final String envLabel;
    private final boolean enabled;

    public ConversationFileService(@Value("${conversation.file.dir:conversations}") String baseDir,
                                    Environment environment) {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        if (profiles.contains("prod")) {
            this.envLabel = "prod";
            this.enabled = true;
        } else if (profiles.contains("dev")) {
            this.envLabel = "dev";
            this.enabled = true;
        } else {
            this.envLabel = null;
            this.enabled = false;
        }
        this.baseDir = Path.of(baseDir);
    }

    // Test-only constructor: bypasses profile detection so tests can force a known enabled/dir state.
    ConversationFileService(Path baseDir, String envLabel, boolean enabled) {
        this.baseDir = baseDir;
        this.envLabel = envLabel;
        this.enabled = enabled;
    }

    public Optional<String> createFile(String email, String displayName, String externalId, OffsetDateTime createTime) {
        if (!enabled) return Optional.empty();
        try {
            Files.createDirectories(baseDir);
            String fileName = resolveUniqueFileName(email, displayName, null);
            String header = "# " + displayName + "\n\n"
                    + "- User: " + emailUser(email) + "\n"
                    + "- External ID: " + externalId + "\n"
                    + "- Created: " + createTime + "\n"
                    + "- Environment: " + envLabel + "\n\n"
                    + "---\n\n";
            Files.writeString(baseDir.resolve(fileName), header, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            return Optional.of(fileName);
        } catch (IOException e) {
            log.error("Failed to create conversation file for '{}'", displayName, e);
            return Optional.empty();
        }
    }

    public void appendMessage(String fileName, String type, String message, OffsetDateTime timestamp) {
        if (!enabled || fileName == null) return;
        try {
            String body = ("tool_call".equals(type) || "tool_result".equals(type))
                    ? "```json\n" + message + "\n```\n"
                    : message + "\n";
            String block = "### " + type + " — " + timestamp + "\n" + body + "\n";
            Files.writeString(baseDir.resolve(fileName), block, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append to conversation file '{}'", fileName, e);
        }
    }

    public Optional<String> renameFile(String currentFileName, String email, String newDisplayName) {
        if (!enabled || currentFileName == null) return Optional.empty();
        try {
            String newFileName = resolveUniqueFileName(email, newDisplayName, currentFileName);
            if (newFileName.equals(currentFileName)) return Optional.of(currentFileName);
            Path source = baseDir.resolve(currentFileName);
            if (!Files.exists(source)) return Optional.empty();
            Files.move(source, baseDir.resolve(newFileName));
            return Optional.of(newFileName);
        } catch (IOException e) {
            log.error("Failed to rename conversation file '{}'", currentFileName, e);
            return Optional.empty();
        }
    }

    private String resolveUniqueFileName(String email, String displayName, String excludeFileName) {
        String base = emailUser(email) + "_" + sanitize(displayName) + "-" + envLabel;
        String candidate = base + ".md";
        int suffix = 2;
        while (!candidate.equals(excludeFileName) && Files.exists(baseDir.resolve(candidate))) {
            candidate = base + " (" + suffix + ").md";
            suffix++;
        }
        return candidate;
    }

    static String emailUser(String email) {
        if (email == null || email.isBlank()) return "guest";
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    static String sanitize(String name) {
        String cleaned = UNSAFE_CHARS.matcher(name == null ? "" : name).replaceAll("_").trim();
        if (cleaned.isEmpty()) cleaned = "conversation";
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH).trim() : cleaned;
    }
}
