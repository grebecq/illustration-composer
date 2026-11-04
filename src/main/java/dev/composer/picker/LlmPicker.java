package dev.composer.picker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.composer.model.Illustration;
import dev.composer.service.LibraryService;
import dev.composer.service.LlmSettings;
import dev.composer.service.LlmSettings.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Подбор по смыслу: модель получает список готовых объектов с тегами и возвращает id подходящих.
 * Рисовать или менять иллюстрации она не может: из ответа берутся только id из библиотеки.
 * Говорит на двух протоколах: OpenAI-совместимом (Ollama, LM Studio, OpenRouter, OpenAI и другие) и Anthropic.
 */
@Component
public class LlmPicker implements ObjectPicker {

    private static final Logger log = LoggerFactory.getLogger(LlmPicker.class);
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[[^\\[\\]]*]");
    private static final String SYSTEM = "Ты подбираешь иллюстрации для композиции. Выбирай только из каталога, "
            + "самые подходящие по смыслу — первыми. Ответ — только JSON-массив id, без пояснений. "
            + "Пример ответа: [\"id1\", \"id2\"].";

    private final LibraryService library;
    private final LlmSettings settings;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public LlmPicker(LibraryService library, LlmSettings settings, ObjectMapper mapper,
                     @Value("${composer.llm.timeout-seconds:45}") long timeoutSeconds) {
        this.library = library;
        this.settings = settings;
        this.mapper = mapper;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public boolean available() {
        return settings.connected();
    }

    /** Для интерфейса {@link ObjectPicker}: любая ошибка даёт пустой список. */
    @Override
    public List<String> pick(String query, int max) {
        try {
            return pickOrThrow(query, max);
        } catch (LlmException e) {
            log.warn("Подбор через ИИ не удался: {}", e.getMessage());
            return List.of();
        }
    }

    public List<String> pickOrThrow(String query, int max) throws LlmException {
        Config c = settings.current().orElseThrow(() -> new LlmException("ИИ не подключён"));
        boolean anthropic = "anthropic".equals(settings.preset(c.provider()).protocol());
        String text = ask(c, anthropic, userPrompt(query, max));
        List<String> ids = parseIds(text, max);
        if (ids.isEmpty()) {
            throw new LlmException("ответ модели не содержит объектов из библиотеки");
        }
        return ids;
    }

    private String ask(Config c, boolean anthropic, String prompt) throws LlmException {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(c.baseUrl() + (anthropic ? "/v1/messages" : "/chat/completions")))
                    .timeout(timeout)
                    .header("content-type", "application/json");
            if (anthropic) {
                b.header("x-api-key", c.apiKey()).header("anthropic-version", "2023-06-01");
            } else if (!c.apiKey().isEmpty()) {
                b.header("authorization", "Bearer " + c.apiKey());
            }
            ObjectNode body = anthropic ? anthropicBody(c, prompt) : openAiBody(c, prompt);
            HttpResponse<String> response = http.send(
                    b.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmException(httpError(response.statusCode(), response.body()));
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode text = anthropic
                    ? root.path("content").path(0).path("text")
                    : root.path("choices").path(0).path("message").path("content");
            return text.asText("");
        } catch (HttpTimeoutException e) {
            throw new LlmException("модель не ответила за " + timeout.toSeconds() + " с");
        } catch (ConnectException e) {
            throw new LlmException("нет связи с " + hostOf(c.baseUrl()));
        } catch (IOException e) {
            throw new LlmException("ошибка связи с " + hostOf(c.baseUrl()) + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("запрос прерван");
        }
    }

    private ObjectNode openAiBody(Config c, String prompt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", c.model());
        root.put("temperature", 0);
        // моделям с «рассуждением» нужен запас: иначе все токены уходят на размышления, а ответ пустой
        root.put("max_tokens", 3000);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM);
        messages.addObject().put("role", "user").put("content", prompt);
        return root;
    }

    private ObjectNode anthropicBody(Config c, String prompt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", c.model());
        root.put("max_tokens", 200);
        root.put("system", SYSTEM);
        root.putArray("messages").addObject().put("role", "user").put("content", prompt);
        return root;
    }

    private String userPrompt(String query, int max) {
        StringBuilder catalog = new StringBuilder();
        for (Illustration o : library.objects()) {
            catalog.append("- ").append(o.id()).append(" (").append(o.name()).append("): ")
                    .append(String.join(", ", o.tags())).append('\n');
        }
        return "Каталог:\n" + catalog + "\nМетафора или заголовок:\n" + query
                + "\n\nВыбери от 2 до " + max + " объектов.";
    }

    /** Берёт id из JSON-массива; если модель ответила словами, ищет id в тексте в порядке упоминания. */
    private List<String> parseIds(String text, int max) {
        List<String> known = library.objects().stream().map(Illustration::id).toList();
        List<String> ids = new ArrayList<>();
        Matcher m = JSON_ARRAY.matcher(text);
        while (ids.isEmpty() && m.find()) {
            try {
                for (String id : mapper.readValue(m.group(), String[].class)) {
                    if (known.contains(id) && !ids.contains(id)) {
                        ids.add(id);
                    }
                }
            } catch (IOException ignored) {
                // это не массив строк, ищем дальше
            }
        }
        if (ids.isEmpty()) {
            known.stream()
                    .filter(id -> Pattern.compile("\\b" + id + "\\b").matcher(text).find())
                    .sorted(Comparator.comparingInt(text::indexOf))
                    .forEach(ids::add);
        }
        return ids.stream().limit(max).toList();
    }

    private static String httpError(int status, String body) {
        return switch (status) {
            case 401, 403 -> "ключ API отклонён (" + status + ")";
            case 404 -> "модель или адрес API не найдены (404)";
            case 429 -> "превышен лимит запросов (429)";
            default -> "ошибка сервера " + status + ": " + body.replaceAll("\\s+", " ").substring(0, Math.min(120, body.length()));
        };
    }

    private static String hostOf(String url) {
        URI u = URI.create(url);
        return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }
}
