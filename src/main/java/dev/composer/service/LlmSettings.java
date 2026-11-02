package dev.composer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Куда отправлять запросы на подбор по смыслу. Подходит любая модель: локальная (Ollama, LM Studio)
 * или по ключу (OpenRouter, OpenAI, Anthropic, любой OpenAI-совместимый сервис).
 * Настройки и ключ живут только в памяти сервера и не возвращаются в браузер.
 *
 * <p>Если ничего не задано, при старте проверяется локальная Ollama.
 */
@Service
public class LlmSettings {

    public record Preset(String id, String name, String protocol, String baseUrl, String model, boolean needsKey) {
    }

    public record Config(String provider, String baseUrl, String model, String apiKey) {
    }

    public static final List<Preset> PRESETS = List.of(
            new Preset("ollama", "Ollama (локально)", "openai", "http://localhost:11434/v1", "qwen2.5:7b", false),
            new Preset("lmstudio", "LM Studio (локально)", "openai", "http://localhost:1234/v1", "local-model", false),
            new Preset("openrouter", "OpenRouter", "openai", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", true),
            new Preset("openai", "OpenAI", "openai", "https://api.openai.com/v1", "gpt-4o-mini", true),
            new Preset("anthropic", "Anthropic", "anthropic", "https://api.anthropic.com", "claude-haiku-5-5", true),
            new Preset("custom", "Другой (OpenAI-совместимый)", "openai", "", "", false));

    private static final Logger log = LoggerFactory.getLogger(LlmSettings.class);
    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7E]+");

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private volatile Config config;

    public LlmSettings(ObjectMapper mapper, Environment env,
                       @Value("${composer.llm.autodetect:true}") boolean autodetect) {
        this.mapper = mapper;
        String provider = env.getProperty("composer.llm.provider", "");
        String anthropicKey = env.getProperty("ANTHROPIC_API_KEY", "");
        String openRouterKey = env.getProperty("OPENROUTER_API_KEY", "");
        try {
            if (!provider.isBlank()) {
                save(provider, env.getProperty("composer.llm.base-url"), env.getProperty("composer.llm.model"),
                        env.getProperty("composer.llm.api-key", ""));
            } else if (!anthropicKey.isBlank()) {
                save("anthropic", null, env.getProperty("composer.llm.model"), anthropicKey);
            } else if (!openRouterKey.isBlank()) {
                save("openrouter", null, env.getProperty("composer.llm.model"), openRouterKey);
            } else if (autodetect) {
                detectOllama();
            }
        } catch (IllegalArgumentException e) {
            log.warn("Настройки ИИ из окружения не приняты: {}", e.getMessage());
        }
    }

    public Optional<Config> current() {
        return Optional.ofNullable(config);
    }

    public boolean connected() {
        return config != null;
    }

    public Preset preset(String id) {
        return PRESETS.stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Неизвестный сервис ИИ: " + id));
    }

    /** {@code apiKey == null} — оставить прежний ключ (если сервис тот же), пустая строка — убрать. */
    public synchronized Config save(String provider, String baseUrl, String model, String apiKey) {
        Preset p = preset(provider);
        String url = (baseUrl == null || baseUrl.isBlank() ? p.baseUrl() : baseUrl.strip()).replaceAll("/+$", "");
        try {
            URI u = URI.create(url);
            if (u.getHost() == null || !(u.getScheme().equals("http") || u.getScheme().equals("https"))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Некорректный адрес API: " + url);
        }
        String m = model == null || model.isBlank() ? p.model() : model.strip();
        if (m.isBlank()) {
            throw new IllegalArgumentException("Укажите модель");
        }
        String key = apiKey != null ? apiKey.strip()
                : (config != null && config.provider().equals(p.id()) ? config.apiKey() : "");
        if (!key.isEmpty() && !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Ключ API содержит недопустимые символы");
        }
        if (p.needsKey() && key.isEmpty()) {
            throw new IllegalArgumentException("Для сервиса «" + p.name() + "» нужен ключ API");
        }
        config = new Config(p.id(), url, m, key);
        return config;
    }

    public synchronized void disconnect() {
        config = null;
    }

    /** Модели, которые сейчас доступны на локальном сервере; пусто, если он не запущен. */
    public List<String> localModels(String provider) {
        try {
            if ("ollama".equals(provider)) {
                return names(get("http://localhost:11434/api/tags"), "models", "name");
            }
            if ("lmstudio".equals(provider)) {
                return names(get("http://localhost:1234/v1/models"), "data", "id");
            }
        } catch (Exception e) {
            log.debug("Локальный сервер {} недоступен: {}", provider, e.toString());
        }
        return List.of();
    }

    private void detectOllama() {
        localModels("ollama").stream()
                .filter((n) -> !n.contains("embed"))
                .findFirst()
                .ifPresent((model) -> {
                    config = new Config("ollama", "http://localhost:11434/v1", model, "");
                    log.info("Найдена локальная Ollama, модель для подбора: {}", model);
                });
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private static List<String> names(JsonNode root, String array, String field) {
        List<String> out = new ArrayList<>();
        root.path(array).forEach((n) -> {
            String name = n.path(field).asText("");
            if (!name.isBlank()) {
                out.add(name);
            }
        });
        return out;
    }
}
