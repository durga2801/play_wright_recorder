package com.example.uirecorder;

import com.example.uirecorder.model.Recording;
import com.example.uirecorder.service.EventNormalizer;
import com.example.uirecorder.service.PlaywrightEventRecorder;
import com.example.uirecorder.service.RecordingWriter;
import com.example.uirecorder.util.ProjectRootResolver;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Scanner;
import java.util.UUID;

public final class RecorderMain {

    // You can configure these directly or override with command-line options.
    private static final String DEFAULT_URL = "http://localhost:4200/";
    private static final String DEFAULT_PROJECT = "sample-ui";
    private static final String DEFAULT_RUN_ID = "RUN001";

    public static void main(String[] args) throws Exception {
        Config config = Config.from(args);
        Path projectRoot = ProjectRootResolver.findCurrentMavenProjectRoot();

        System.out.println("Current Maven project : " + projectRoot);
        System.out.println("URL                   : " + config.url);
        System.out.println("Project               : " + config.project);
        System.out.println("Run ID                : " + config.runId);
        System.out.println();
        System.out.println("The browser will open. Perform the UI journey normally.");
        System.out.println("When finished, return to this console and press ENTER.");

        try (PlaywrightEventRecorder recorder = new PlaywrightEventRecorder(false);
             Scanner scanner = new Scanner(System.in)) {
            recorder.open(config.url);
            scanner.nextLine();

            var normalized = new EventNormalizer().normalize(recorder.readEvents());
            var recording = new Recording(config.project, config.runId, config.url, Instant.now().toString(), normalized);
            Path output = new RecordingWriter().write(projectRoot, recording);

            System.out.println("Recorded " + normalized.size() + " normalized event(s).");
            System.out.println("Recording written to: " + output);
            System.out.println("Use the generated COPILOT-REQUESTS.md or ask Copilot directly to generate Playwright/Cucumber or Selenium/Cucumber tests.");
        }
    }

    private record Config(String url, String project, String runId) {
        static Config from(String[] args) {
            String url = DEFAULT_URL;
            String project = DEFAULT_PROJECT;
            String runId = DEFAULT_RUN_ID;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--url" -> url = args[++i];
                    case "--project" -> project = args[++i];
                    case "--runId" -> runId = args[++i];
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            if (runId == null || runId.isBlank()) {
                runId = "RUN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            }
            return new Config(url, project, runId);
        }
    }
}
