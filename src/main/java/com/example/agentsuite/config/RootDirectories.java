package com.example.agentsuite.config;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * The allowlist of root directories the AI may operate in. The empty string
 * means "no root selected" and is valid for requests but meaningless for
 * filesystem scanning — use {@link #nonEmpty()} for the latter.
 */
public final class RootDirectories {

    public static final Set<String> ALLOWED = Set.of(
            "",
            "C:/Users/Lenovo/misc_projects/dragon",
            "C:/Users/Lenovo/misc_projects/gexplorer",
            "C:/Users/Lenovo/IdeaProjects/agent-suite",
            "C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian"
    );

    private RootDirectories() {}

    public static Set<String> nonEmpty() {
        return ALLOWED.stream()
                .filter(dir -> !dir.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
