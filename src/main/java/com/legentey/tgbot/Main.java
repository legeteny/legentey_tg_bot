package com.legentey.tgbot;

import com.legentey.tgbot.bot.BotService;
import com.legentey.tgbot.storage.DatabaseMigrator;
import com.legentey.tgbot.storage.SqliteApprovalSettingsStore;
import com.legentey.tgbot.storage.SqliteDeployStore;
import com.legentey.tgbot.storage.SqliteQuoteStickerStore;
import com.legentey.tgbot.storage.SqliteRegularStickerPackStore;
import com.legentey.tgbot.storage.SqliteRenderSettingsStore;
import com.legentey.tgbot.storage.SqliteStickerPackStore;
import com.legentey.tgbot.storage.SqliteUpdateOffsetStore;
import com.legentey.tgbot.storage.SqliteUserStore;
import com.legentey.tgbot.telegram.TelegramApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Config config = Config.fromEnv();

        DatabaseMigrator.migrate(config.dbPath());

        SqliteUserStore store = new SqliteUserStore(config.dbPath());
        SqliteStickerPackStore stickerPackStore = new SqliteStickerPackStore(config.dbPath());
        SqliteQuoteStickerStore quoteStickerStore = new SqliteQuoteStickerStore(config.dbPath());
        SqliteRenderSettingsStore renderSettingsStore = new SqliteRenderSettingsStore(config.dbPath());
        SqliteDeployStore deployStore = new SqliteDeployStore(config.dbPath());
        SqliteUpdateOffsetStore updateOffsetStore = new SqliteUpdateOffsetStore(config.dbPath());
        SqliteApprovalSettingsStore approvalSettingsStore =
                new SqliteApprovalSettingsStore(config.dbPath(), "approval_settings");
        SqliteRegularStickerPackStore regularStickerPackStore = new SqliteRegularStickerPackStore(config.dbPath());
        SqliteApprovalSettingsStore stickerApprovalSettingsStore =
                new SqliteApprovalSettingsStore(config.dbPath(), "sticker_approval_settings");
        TelegramApiClient client = new TelegramApiClient(config.botToken());
        BotService botService = new BotService(client, store, stickerPackStore, quoteStickerStore,
                renderSettingsStore, deployStore, updateOffsetStore, approvalSettingsStore, regularStickerPackStore,
                stickerApprovalSettingsStore, config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            botService.stop();
            store.close();
            stickerPackStore.close();
            quoteStickerStore.close();
            renderSettingsStore.close();
            deployStore.close();
            updateOffsetStore.close();
            approvalSettingsStore.close();
            regularStickerPackStore.close();
            stickerApprovalSettingsStore.close();
        }, "shutdown-hook"));

        log.info("Starting Telegram tag-all bot");
        botService.run();
    }
}
