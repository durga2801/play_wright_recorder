package com.example.uirecorder.service;

import com.example.uirecorder.model.Recording;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RecordingWriter {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public Path write(Path projectRoot, Recording recording) throws IOException {
        Path outputDir = projectRoot
                .resolve("src/test/resources/recordings")
                .resolve(sanitize(recording.project()))
                .resolve(sanitize(recording.runId()));
        Files.createDirectories(outputDir);

        Path json = outputDir.resolve("recording.json");
        mapper.writeValue(json.toFile(), recording);

        Files.writeString(outputDir.resolve("COPILOT-REQUESTS.md"), copilotRequests(recording));
        return json;
    }

    private String copilotRequests(Recording r) {
        String rel = "src/test/resources/recordings/" + sanitize(r.project()) + "/" + sanitize(r.runId()) + "/recording.json";
        return """
                # GitHub Copilot generation requests

                This recording was created by the Playwright event recorder. The recorder itself does not generate test code.

                ## Playwright + Java + Cucumber

                Ask Copilot:

                ```text
                Read %s and .github/copilot-instructions.md.
                Generate Playwright Java Cucumber automation under src/test/java for this recorded journey.
                Preserve action order and do not invent actions.
                ```

                ## Selenium + Java + Cucumber

                Ask Copilot:

                ```text
                Read %s and .github/copilot-instructions.md.
                Generate Selenium Java Cucumber automation under src/test/java for this recorded journey.
                Preserve action order and do not invent actions.
                ```
                """.formatted(rel, rel);
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
