package com.legentey.tgbot.storage;

import java.util.Optional;

/**
 * Remembers which commit the bot last announced on startup, so the next startup can
 * report everything that landed since — including commits from a deploy that never
 * got announced because the notification failed.
 */
public interface DeployStore {

    Optional<String> lastAnnouncedCommit();

    void saveAnnouncedCommit(String commitHash);
}
