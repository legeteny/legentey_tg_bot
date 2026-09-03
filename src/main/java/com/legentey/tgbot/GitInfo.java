package com.legentey.tgbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Identifies which commits the running bot was built from.
 *
 * <p>In production the deploy workflow checks out the repo on the server and writes
 * {@code GIT_COMMIT}/{@code GIT_COMMIT_MESSAGE}, plus a window of recent history in
 * {@code GIT_RECENT_COMMITS}, into {@code .env} before starting the container, so those
 * env vars are the primary source — the image itself ships neither {@code git} nor a
 * checkout. For local runs (e.g. from an IDE) there is no such env var, so this falls
 * back to asking the local {@code git} checkout directly.
 *
 * <p>The window arrives base64-encoded because docker-compose parses {@code env_file}
 * line by line: a multi-line value would not survive it, and {@code #}, quotes or
 * {@code $} in commit subjects would break the parse.
 */
public record GitInfo(String hash, String message, List<Commit> recent) {

    private static final Logger log = LoggerFactory.getLogger(GitInfo.class);

    /** How much history the deploy workflow is asked for, and the local fallback reads. */
    private static final int WINDOW = 50;

    /** One line of {@code git log --pretty=%h %s}. */
    public record Commit(String hash, String subject) {
    }

    public static GitInfo detect() {
        String envHash = System.getenv("GIT_COMMIT");
        if (envHash != null && !envHash.isBlank()) {
            String envMessage = System.getenv("GIT_COMMIT_MESSAGE");
            return new GitInfo(envHash.trim(),
                    envMessage == null ? "" : envMessage.trim(),
                    decodeRecent(System.getenv("GIT_RECENT_COMMITS")));
        }

        try {
            String hash = runGit("git", "rev-parse", "--short", "HEAD");
            String message = runGit("git", "log", "-1", "--pretty=%s");
            return new GitInfo(hash, message, localRecent());
        } catch (Exception e) {
            return new GitInfo("unknown", "", List.of());
        }
    }

    /** Missing or malformed history degrades to an empty window, never to a failed startup. */
    private static List<Commit> decodeRecent(String base64) {
        if (base64 == null || base64.isBlank()) {
            return List.of();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64.trim());
            return parseLog(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            log.warn("GIT_RECENT_COMMITS is not valid base64, ignoring it: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<Commit> localRecent() {
        try {
            return parseLog(runGit("git", "log", "-" + WINDOW, "--pretty=%h %s"));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Newest first, matching {@code git log} order. */
    private static List<Commit> parseLog(String text) {
        List<Commit> commits = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int space = trimmed.indexOf(' ');
            if (space < 0) {
                commits.add(new Commit(trimmed, ""));
            } else {
                commits.add(new Commit(trimmed.substring(0, space), trimmed.substring(space + 1).trim()));
            }
        }
        return List.copyOf(commits);
    }

    private static String runGit(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0 || output.isBlank()) {
            throw new IOException("git command failed: " + String.join(" ", command));
        }
        return output;
    }
}
