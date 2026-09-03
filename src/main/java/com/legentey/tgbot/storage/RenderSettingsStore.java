package com.legentey.tgbot.storage;

/** The quote-api render width (see QuoteApiClient), changeable at runtime via /q_set_width. */
public interface RenderSettingsStore {

    int getWidth();

    void setWidth(int width);
}
