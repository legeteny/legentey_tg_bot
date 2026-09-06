package com.legentey.tgbot.storage;

/**
 * How many chat members, other than whoever asked, must approve before /del
 * removes a sticker. Changeable at runtime via /q_set_approve_count (for a /q
 * quote) or /s_set_approve_count (for a plain /s sticker) — each kind keeps its
 * own instance of this store. 0 means a sticker is deleted on request, with no
 * vote at all.
 */
public interface ApprovalSettingsStore {

    int getApproveCount();

    void setApproveCount(int count);

    /** How long an unfinished /del vote stays open, in seconds. */
    int getApproveTimeoutSeconds();

    void setApproveTimeoutSeconds(int seconds);
}
