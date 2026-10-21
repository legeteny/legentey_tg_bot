package com.legentey.tgbot.storage;

/** The per-user /all cooldown, changeable at runtime via /rate_limit_set. 0 means no limit. */
public interface RateLimitSettingsStore {

    int getAllCooldownSeconds();

    void setAllCooldownSeconds(int seconds);
}
