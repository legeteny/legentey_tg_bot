-- Baseline: the schema exactly as it existed before Flyway was introduced, back
-- when each Sqlite*Store built its own tables with ad-hoc "CREATE TABLE IF NOT
-- EXISTS" / "ALTER TABLE ... ADD COLUMN" calls at startup. On every database that
-- already exists in the wild, Flyway's baselineOnMigrate marks this version as
-- already applied instead of running it — only a brand-new, empty database
-- actually executes this file.

CREATE TABLE chat_users (
    chat_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    username TEXT,
    first_name TEXT,
    last_name TEXT,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (chat_id, user_id)
);

CREATE TABLE chat_sticker_packs (
    chat_id INTEGER PRIMARY KEY,
    pack_name TEXT NOT NULL,
    owner_user_id INTEGER NOT NULL
);

CREATE TABLE chat_regular_sticker_packs (
    chat_id INTEGER PRIMARY KEY,
    pack_name TEXT NOT NULL,
    owner_user_id INTEGER NOT NULL
);

CREATE TABLE quote_stickers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id INTEGER NOT NULL,
    pack_name TEXT NOT NULL,
    owner_user_id INTEGER NOT NULL,
    file_id TEXT NOT NULL,
    from_json TEXT NOT NULL,
    text TEXT NOT NULL,
    entities_json TEXT,
    emoji TEXT NOT NULL,
    avatar_file_id TEXT,
    emoji_status TEXT,
    username TEXT
);

CREATE INDEX idx_quote_stickers_pack ON quote_stickers(pack_name, id);

CREATE TABLE render_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    width INTEGER NOT NULL
);

INSERT INTO render_settings (id, width) VALUES (1, 280);

CREATE TABLE deploy_state (
    id INTEGER PRIMARY KEY CHECK (id = 0),
    last_commit TEXT NOT NULL
);

CREATE TABLE update_offset (
    id INTEGER PRIMARY KEY CHECK (id = 0),
    next_update_id INTEGER NOT NULL
);

-- One row each for /q_del and /s_del's independent approval settings (see
-- SqliteApprovalSettingsStore, which is instantiated twice against these two
-- table names).
CREATE TABLE approval_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    approve_count INTEGER NOT NULL,
    approve_timeout_seconds INTEGER NOT NULL DEFAULT 300
);

INSERT INTO approval_settings (id, approve_count) VALUES (1, 2);

CREATE TABLE sticker_approval_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    approve_count INTEGER NOT NULL,
    approve_timeout_seconds INTEGER NOT NULL DEFAULT 300
);

INSERT INTO sticker_approval_settings (id, approve_count) VALUES (1, 2);
