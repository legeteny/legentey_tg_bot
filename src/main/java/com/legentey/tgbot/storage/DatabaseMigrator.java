package com.legentey.tgbot.storage;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Runs Flyway's SQL migrations (src/main/resources/db/migration) against the
 * bot's SQLite file once at startup, before any Sqlite*Store opens its own
 * connection. Schema creation and evolution lives here now, not scattered
 * across each store's own ad-hoc "CREATE TABLE IF NOT EXISTS" / "ALTER TABLE"
 * calls.
 *
 * <p>{@code baselineOnMigrate(true)} is what makes this safe to introduce on a
 * database that already has these tables — which is every existing deployment.
 * Flyway detects a non-empty schema with no history table, marks V1 (which
 * recreates exactly that pre-Flyway schema) as already applied without
 * executing it, and runs anything newer for real. A brand-new database has
 * none of that baggage, so V1 runs for real there instead.
 */
public final class DatabaseMigrator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    private DatabaseMigrator() {
    }

    public static void migrate(String dbPath) {
        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:sqlite:" + dbPath, null, null)
                .baselineOnMigrate(true)
                .locations("classpath:db/migration")
                .load();
        MigrateResult result = flyway.migrate();
        log.info("Database migrations applied: {}", result.migrationsExecuted);
    }
}
