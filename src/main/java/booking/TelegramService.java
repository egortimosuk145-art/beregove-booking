package booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Надсилає власнику повідомлення в Telegram про нове бронювання
 * з кнопкою "Скасувати", та опитує Telegram (long polling), щоб
 * дізнатись, коли власник цю кнопку натиснув — і тоді видаляє
 * бронювання з бази, після чого дата одразу знову стає вільною на сайті.
 */
@Service
public class TelegramService {

    @Value("${telegram.bot.token:}")
    private String botToken;

    @Value("${telegram.chat.id:}")
    private String chatId;

    private final BookingRepository bookingRepository;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile long lastUpdateId = 0;

    private static final Map<String, String> SERVICE_NAMES = Map.of(
            "forest", "Forest House",
            "sunset", "Sunset House",
            "pool", "Басейн",
            "chan", "Чан",
            "lazna", "Лазня"
    );

    public TelegramService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    private boolean isConfigured() {
        return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
    }

    private String apiUrl(String method) {
        return "https://api.telegram.org/bot" + botToken + "/" + method;
    }

    /** Викликається після успішного збереження бронювання. */
    public void sendBookingNotification(Booking b) {
        if (!isConfigured()) {
            System.out.println("Telegram не налаштований (telegram.bot.token / telegram.chat.id) — сповіщення не надіслано.");
            return;
        }
        try {
            String serviceName = SERVICE_NAMES.getOrDefault(b.getService(), b.getService());
            StringBuilder text = new StringBuilder();
            text.append("🌿 НОВА ЗАЯВКА — BEREGOVE\n\n");
            text.append("Послуга: ").append(serviceName).append("\n");
            text.append("Дата: ").append(b.getDate());
            if (b.getNights() != null && b.getNights() > 1) {
                text.append(" (").append(b.getNights()).append(" ніч.)");
            }
            text.append("\n");
            if (b.getTimeSlot() != null && !b.getTimeSlot().isBlank()) {
                text.append("Час: ").append(b.getTimeSlot()).append("\n");
            }
            if (b.getGuests() != null) {
                text.append("Гості: ").append(b.getGuests());
                if (b.getChildren() != null && b.getChildren() > 0) {
                    text.append(" дорослих, ").append(b.getChildren()).append(" дітей");
                }
                text.append("\n");
            }
            if (b.getTotal() != null) {
                text.append("Орієнтовна сума: ").append(b.getTotal()).append(" грн\n");
            }
            text.append("\nІм'я: ").append(b.getCustomerName()).append("\n");
            text.append("Контакт: ").append(b.getCustomerContact()).append("\n");
            if (b.getComment() != null && !b.getComment().isBlank()) {
                text.append("Коментар: ").append(b.getComment()).append("\n");
            }

            Map<String, Object> button = Map.of(
                    "text", "❌ Скасувати бронювання",
                    "callback_data", "cancel:" + b.getId()
            );
            Map<String, Object> keyboard = Map.of("inline_keyboard", List.of(List.of(button)));

            Map<String, Object> payload = Map.of(
                    "chat_id", chatId,
                    "text", text.toString(),
                    "reply_markup", keyboard
            );

            send("sendMessage", payload);
        } catch (Exception e) {
            System.err.println("Не вдалось надіслати повідомлення в Telegram: " + e.getMessage());
        }
    }

    /** Раз на кілька секунд перевіряє, чи власник натиснув "Скасувати" під якимось із повідомлень. */
    @Scheduled(fixedDelay = 3000)
    public void pollUpdates() {
        if (!isConfigured()) return;
        try {
            String url = apiUrl("getUpdates") + "?timeout=0&offset=" + (lastUpdateId + 1);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            if (!root.path("ok").asBoolean(false)) return;

            for (JsonNode update : root.path("result")) {
                lastUpdateId = Math.max(lastUpdateId, update.path("update_id").asLong());

                JsonNode callback = update.path("callback_query");
                if (callback.isMissingNode()) continue;

                String data = callback.path("data").asText("");
                String callbackId = callback.path("id").asText("");
                JsonNode message = callback.path("message");
                long messageId = message.path("message_id").asLong();
                String fromChatId = message.path("chat").path("id").asText("");
                String originalText = message.path("text").asText("");

                if (data.startsWith("cancel:")) {
                    handleCancel(data.substring("cancel:".length()), callbackId, fromChatId, messageId, originalText);
                }
            }
        } catch (Exception e) {
            System.err.println("Помилка опитування Telegram: " + e.getMessage());
        }
    }

    private void handleCancel(String idStr, String callbackId, String chatId, long messageId, String originalText) {
        try {
            Long bookingId = Long.parseLong(idStr);
            if (bookingRepository.existsById(bookingId)) {
                bookingRepository.deleteById(bookingId);
                answerCallback(callbackId, "Бронювання скасовано, дата знову вільна на сайті");
                editMessage(chatId, messageId, originalText + "\n\n❌ СКАСОВАНО");
            } else {
                answerCallback(callbackId, "Це бронювання вже було скасовано раніше");
            }
        } catch (Exception e) {
            System.err.println("Не вдалось скасувати бронювання: " + e.getMessage());
        }
    }

    private void answerCallback(String callbackId, String text) throws Exception {
        send("answerCallbackQuery", Map.of("callback_query_id", callbackId, "text", text));
    }

    private void editMessage(String chatId, long messageId, String newText) throws Exception {
        Map<String, Object> payload = Map.of(
                "chat_id", chatId,
                "message_id", messageId,
                "text", newText
        );
        send("editMessageText", payload);
    }

    private void send(String method, Map<String, Object> payload) throws Exception {
        String body = mapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl(method)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
