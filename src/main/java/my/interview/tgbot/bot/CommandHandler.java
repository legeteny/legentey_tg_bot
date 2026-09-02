package my.interview.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import my.interview.tgbot.Config;
import my.interview.tgbot.storage.ChatUser;
import my.interview.tgbot.storage.UserStore;
import my.interview.tgbot.telegram.TelegramApiClient;
import my.interview.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    private static final String HELP_TEXT = """
            Привет! Я умею тегать всех известных мне участников группы.

            Команды:
            /tagall [текст] — упомянуть всех известных участников чата (можно добавить текст объявления)
            /tagme — включить себя в список для упоминания
            /notagme — исключить себя из списка (я не буду вас упоминать)
            /count — сколько участников я сейчас знаю в этом чате
            /help — это сообщение

            Важно: у ботов нет доступа к полному списку участников группы через API Telegram.
            Я запоминаю только тех, кого видел — кто писал сообщения в чате, пока я в нём состою,
            либо кто вступал в группу после моего добавления. Поэтому сразу после добавления
            список может быть неполным — он пополнится по мере переписки.

            Чтобы я видел все сообщения (а не только команды), отключите Privacy Mode у бота
            через @BotFather (/setprivacy → Disable), либо назначьте бота администратором группы.
            """;

    private final TelegramApiClient client;
    private final UserStore store;
    private final Config config;

    public CommandHandler(TelegramApiClient client, UserStore store, Config config) {
        this.client = client;
        this.store = store;
        this.config = config;
    }

    public void handle(long chatId, long messageId, boolean isGroup, JsonNode from, String text) {
        String[] parts = text.trim().split("\\s+", 2);
        String rawCommand = parts[0];
        String args = parts.length > 1 ? parts[1].trim() : "";

        int at = rawCommand.indexOf('@');
        String command = (at >= 0 ? rawCommand.substring(1, at) : rawCommand.substring(1))
                .toLowerCase(Locale.ROOT);

        long senderId = from.path("id").asLong();
        String username = textOrNull(from, "username");
        String firstName = textOrNull(from, "first_name");
        String lastName = textOrNull(from, "last_name");

        switch (command) {
            case "start", "help" -> client.sendMessage(chatId, HELP_TEXT, null, null);
            case "tagall", "all", "everyone" -> handleTagAll(chatId, messageId, isGroup, senderId, args);
            case "tagme" -> {
                store.upsertUser(chatId, senderId, username, firstName, lastName);
                store.setOptedOut(chatId, senderId, false);
                client.sendMessage(chatId, "Готово, теперь я буду упоминать вас в /tagall.", null, messageId);
            }
            case "notagme" -> {
                store.upsertUser(chatId, senderId, username, firstName, lastName);
                store.setOptedOut(chatId, senderId, true);
                client.sendMessage(chatId, "Хорошо, больше не буду упоминать вас в /tagall.", null, messageId);
            }
            case "count" -> {
                int count = store.countChatUsers(chatId);
                client.sendMessage(chatId, "Я знаю " + count + " участник(ов) этого чата.", null, messageId);
            }
            default -> {
                // Unknown command — stay quiet to avoid spamming unrelated bot commands in the group.
            }
        }
    }

    private void handleTagAll(long chatId, long messageId, boolean isGroup, long senderId, String args) {
        if (!isGroup) {
            client.sendMessage(chatId, "Эта команда работает только в группах.", null, messageId);
            return;
        }

        if (config.tagAllAdminOnly() && !isAdmin(chatId, senderId)) {
            client.sendMessage(chatId, "⛔ Эту команду может использовать только администратор группы.", null, messageId);
            return;
        }

        List<ChatUser> users = store.listChatUsers(chatId).stream()
                .filter(u -> !u.optedOut())
                .collect(Collectors.toList());

        if (users.isEmpty()) {
            client.sendMessage(chatId,
                    "Пока не знаю ни одного участника этого чата для упоминания. "
                            + "Я запоминаю участников по мере того, как они пишут сообщения.",
                    null, messageId);
            return;
        }

        String header = args.isBlank() ? "📢 Внимание всем:" : "📢 " + args;
        List<String> chunks = MentionFormatter.buildMentionMessages(users, header);

        for (int i = 0; i < chunks.size(); i++) {
            client.sendMessage(chatId, chunks.get(i), "MarkdownV2", i == 0 ? messageId : null);
            if (i < chunks.size() - 1) {
                sleepQuietly(1200);
            }
        }
    }

    private boolean isAdmin(long chatId, long userId) {
        try {
            JsonNode admins = client.getChatAdministrators(chatId);
            for (JsonNode member : admins) {
                if (member.path("user").path("id").asLong() == userId) {
                    return true;
                }
            }
            return false;
        } catch (TelegramApiException e) {
            log.warn("Failed to fetch chat administrators for chat {}: {}", chatId, e.getMessage());
            // Fail closed: if we can't verify admin rights, don't allow a mass-mention.
            return false;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
