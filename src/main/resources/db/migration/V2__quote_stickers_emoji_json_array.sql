-- /q and /s used to tag a sticker with exactly one emoji, stored here as a bare
-- character (e.g. "😀"). Now that a sticker can carry several emoji in order,
-- this column holds a JSON array (e.g. ["😀","😄"]) instead. Rows written before
-- this change still hold the old bare-character shape, so every one of those
-- gets wrapped into a single-element array here; a row already in the new shape
-- (json_valid + a top-level array) is left untouched.
UPDATE quote_stickers
SET emoji = json_array(emoji)
WHERE json_valid(emoji) = 0 OR json_type(emoji) != 'array';
