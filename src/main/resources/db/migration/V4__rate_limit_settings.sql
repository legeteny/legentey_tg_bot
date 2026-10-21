-- How often one user may call /all in one chat, changeable at runtime via
-- /rate_limit_set. Seeded with the 60 seconds it was hardcoded to before.
CREATE TABLE rate_limit_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    all_cooldown_seconds INTEGER NOT NULL
);

INSERT INTO rate_limit_settings (id, all_cooldown_seconds) VALUES (1, 60);
