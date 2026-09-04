package com.legentey.tgbot.storage;

/**
 * How many chat members, other than whoever asked, must approve before /q_del
 * removes a quote. Changeable at runtime via /q_set_approve_count; 0 means a
 * quote is deleted on request, with no vote at all.
 */
public interface ApprovalSettingsStore {

    int getApproveCount();

    void setApproveCount(int count);

    /** How long an unfinished /q_del vote stays open, in seconds. */
    int getApproveTimeoutSeconds();

    void setApproveTimeoutSeconds(int seconds);
}
