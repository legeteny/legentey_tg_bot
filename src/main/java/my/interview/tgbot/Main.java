package my.interview.tgbot;

import my.interview.tgbot.bot.BotService;
import my.interview.tgbot.storage.SqliteStickerPackStore;
import my.interview.tgbot.storage.SqliteUserStore;
import my.interview.tgbot.telegram.TelegramApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Config config = Config.fromEnv();

        SqliteUserStore store = new SqliteUserStore(config.dbPath());
        SqliteStickerPackStore stickerPackStore = new SqliteStickerPackStore(config.dbPath());
        TelegramApiClient client = new TelegramApiClient(config.botToken());
        BotService botService = new BotService(client, store, stickerPackStore, config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            botService.stop();
            store.close();
            stickerPackStore.close();
        }, "shutdown-hook"));

        log.info("Starting Telegram tag-all bot");
        botService.run();
    }
}
