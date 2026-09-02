package my.interview.tgbot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Identifies which commit the running bot was built from.
 *
 * <p>In production the deploy workflow checks out the repo on the server and writes
 * {@code GIT_COMMIT}/{@code GIT_COMMIT_MESSAGE} into {@code .env} before starting the
 * container, so those env vars are the primary source. For local runs (e.g. from an IDE)
 * there is no such env var, so this falls back to asking the local {@code git} checkout directly.
 */
public record GitInfo(String hash, String message) {

    public static GitInfo detect() {
        String envHash = System.getenv("GIT_COMMIT");
        if (envHash != null && !envHash.isBlank()) {
            String envMessage = System.getenv("GIT_COMMIT_MESSAGE");
            return new GitInfo(envHash.trim(), envMessage == null ? "" : envMessage.trim());
        }

        try {
            String hash = runGit("git", "rev-parse", "--short", "HEAD");
            String message = runGit("git", "log", "-1", "--pretty=%s");
            return new GitInfo(hash, message);
        } catch (Exception e) {
            return new GitInfo("unknown", "");
        }
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
