package com.legentey.tgbot.bot;

import com.legentey.tgbot.GitInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the message the bot sends to the admin on startup: every commit that landed
 * since the last one it announced, rather than just the commit it happens to run.
 *
 * <p>Kept free of I/O so each branch can be exercised directly.
 */
final class StartupMessage {

    /** Telegram rejects anything longer; see sendMessage limits. */
    private static final int MAX_LENGTH = 4096;

    /** Beyond this the list is summarised — a long-dormant bot should not spam a wall of commits. */
    private static final int MAX_LISTED = 30;

    private StartupMessage() {
    }

    static String build(GitInfo git, Optional<String> lastAnnounced) {
        if (lastAnnounced.isEmpty()) {
            return clamp(currentCommit("🚀 Бот запущен", git));
        }
        if (sameCommit(lastAnnounced.get(), git.hash())) {
            return clamp(currentCommit("🚀 Бот перезапущен", git));
        }

        List<GitInfo.Commit> since = commitsSince(git.recent(), lastAnnounced.get());
        if (since.isEmpty()) {
            // Either no history was passed in, or the remembered commit fell out of the
            // window (rewritten history, or a very long gap) — the range is unknowable.
            return clamp(currentCommit("🚀 Бот запущен", git));
        }
        return clamp(commitList(since));
    }

    private static String currentCommit(String header, GitInfo git) {
        return header + "\nКоммит: " + git.hash()
                + (git.message().isBlank() ? "" : "\n" + git.message());
    }

    private static String commitList(List<GitInfo.Commit> commits) {
        StringBuilder sb = new StringBuilder("🚀 Бот обновлён\n\nНовые коммиты (")
                .append(commits.size())
                .append("):");
        for (GitInfo.Commit commit : commits.subList(0, Math.min(commits.size(), MAX_LISTED))) {
            sb.append("\n").append(commit.hash());
            if (!commit.subject().isBlank()) {
                sb.append(" ").append(commit.subject());
            }
        }
        if (commits.size() > MAX_LISTED) {
            sb.append("\n…и ещё ").append(commits.size() - MAX_LISTED);
        }
        return sb.toString();
    }

    /**
     * Commits newer than {@code lastAnnounced}, newest first. Empty when the remembered
     * commit is not in the window at all, which is deliberately indistinguishable from
     * "nothing new" — both mean the caller cannot state a range.
     */
    private static List<GitInfo.Commit> commitsSince(List<GitInfo.Commit> recent, String lastAnnounced) {
        List<GitInfo.Commit> since = new ArrayList<>();
        for (GitInfo.Commit commit : recent) {
            if (sameCommit(commit.hash(), lastAnnounced)) {
                return List.copyOf(since);
            }
            since.add(commit);
        }
        return List.of();
    }

    /** The remembered hash and the one in the log may be abbreviated to different lengths. */
    private static boolean sameCommit(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        return a.startsWith(b) || b.startsWith(a);
    }

    private static String clamp(String text) {
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH - 1) + "…";
    }
}
