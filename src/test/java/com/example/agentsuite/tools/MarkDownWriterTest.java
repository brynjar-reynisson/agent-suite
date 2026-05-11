package com.example.agentsuite.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MarkDownWriterTest {

    @TempDir
    Path tempDir;

    private MarkDownWriter writer;

    @BeforeEach
    void setUp() {
        writer = new MarkDownWriter(tempDir.toString());
    }

    @Test
    void newMarkDownFile_specType_createsFileInSpecsDirectory() {
        String result = writer.newMarkDownFile("spec", "my-feature", "# Content");
        assertThat(result).startsWith("Successfully wrote");
        assertThat(tempDir.resolve("docs/specs").toFile().listFiles()).hasSize(1);
    }

    @Test
    void newMarkDownFile_planType_createsFileInPlansDirectory() {
        String result = writer.newMarkDownFile("plan", "my-feature", "# Plan");
        assertThat(result).startsWith("Successfully wrote");
        assertThat(tempDir.resolve("docs/plans").toFile().listFiles()).hasSize(1);
    }

    @Test
    void newMarkDownFile_invalidDocumentType_returnsError() {
        String result = writer.newMarkDownFile("invalid", "my-feature", "content");
        assertThat(result).contains("Error: Unknown document type");
    }

    @Test
    void newMarkDownFile_fileAlreadyExists_returnsError() {
        writer.newMarkDownFile("spec", "my-feature", "first");
        String result = writer.newMarkDownFile("spec", "my-feature", "second");
        assertThat(result).contains("Error: File already exists");
    }

    @Test
    void newMarkDownFile_specialCharsInFeatureName_sanitisesToKebabCase() {
        String result = writer.newMarkDownFile("spec", "My Feature! With Spaces", "content");
        assertThat(result).startsWith("Successfully wrote");
        assertThat(result).contains("my-feature-with-spaces");
    }
}
