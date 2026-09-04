package com.example.uirecorder.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ProjectRootResolver {
    private ProjectRootResolver() {}

    public static Path findCurrentMavenProjectRoot() {
        Path current = Paths.get("./ui-recorder-copilot-project").toAbsolutePath().normalize();
        Path candidate = current;
        while (candidate != null) {
            if (Files.exists(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate pom.xml from working directory: " + current);
    }
}
