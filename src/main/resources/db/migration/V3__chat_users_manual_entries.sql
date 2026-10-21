-- /add can put someone on a chat's /all list by @username alone, and the Bot API
-- has no way to turn a username into a user id. So user_id becomes nullable: a
-- NULL there is a hand-added "@username only" row, which SqliteUserStore folds
-- into the real one as soon as that person writes in the chat. SQLite can't relax
-- a PRIMARY KEY in place, hence the rebuild; the old key's uniqueness lives on as
-- a partial index over the rows that do have an id.
CREATE TABLE chat_users_new (
    chat_id INTEGER NOT NULL,
    user_id INTEGER,
    username TEXT,
    first_name TEXT,
    last_name TEXT,
    updated_at INTEGER NOT NULL,
    CHECK (user_id IS NOT NULL OR username IS NOT NULL)
);

INSERT INTO chat_users_new (chat_id, user_id, username, first_name, last_name, updated_at)
SELECT chat_id, user_id, username, first_name, last_name, updated_at FROM chat_users;

DROP TABLE chat_users;

ALTER TABLE chat_users_new RENAME TO chat_users;

CREATE UNIQUE INDEX ux_chat_users_user ON chat_users(chat_id, user_id) WHERE user_id IS NOT NULL;

-- Usernames are case-insensitive in Telegram: @Foo and @foo are the same person.
CREATE UNIQUE INDEX ux_chat_users_manual ON chat_users(chat_id, lower(username)) WHERE user_id IS NULL;
