package dev.composer.web;

import dev.composer.picker.LlmException;
import dev.composer.picker.LlmPicker;
import dev.composer.service.LlmSettings;
import dev.composer.service.LlmSettings.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Подключение ИИ для подбора по метафоре. Ключ принимается, но наружу никогда не отдаётся. */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    /** {@code apiKey == null} — оставить прежний ключ. */
    public record Settings(String provider, String baseUrl, String model, String apiKey) {
    }

    private final LlmSettings settings;
    private final LlmPicker picker;

    private final boolean locked;

    public LlmController(LlmSettings settings, LlmPicker picker,
                         @Value("${composer.llm.locked:false}") boolean locked) {
        this.settings = settings;
        this.picker = picker;
        this.locked = locked;
    }

    /** Для публичного показа: подключённую модель можно использовать, но нельзя менять. */
    private void requireUnlocked() {
        if (locked) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Настройки ИИ закрыты");
        }
    }

    @GetMapping
    public Map<String, Object> state() {
        return describe();
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody Settings s) {
        requireUnlocked();
        settings.save(s.provider(), s.baseUrl(), s.model(), s.apiKey());
        return describe();
    }

    @DeleteMapping
    public Map<String, Object> disconnect() {
        requireUnlocked();
        settings.disconnect();
        return describe();
    }

    /** Реальный запрос к модели: проверяет адрес, ключ и то, что модель отвечает по делу. */
    @PostMapping("/test")
    public Map<String, Object> test() {
        requireUnlocked();
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<String> ids = picker.pickOrThrow("обучение и финансы", 3);
            out.put("ok", true);
            out.put("picked", ids);
        } catch (LlmException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    @GetMapping("/models")
    public List<String> models(@RequestParam String provider) {
        requireUnlocked();
        return settings.localModels(provider);
    }

    private Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        Config c = settings.current().orElse(null);
        out.put("connected", c != null);
        out.put("locked", locked);
        if (c != null) {
            out.put("provider", c.provider());
            out.put("providerName", settings.preset(c.provider()).name());
            out.put("baseUrl", c.baseUrl());
            out.put("model", c.model());
            out.put("hasKey", !c.apiKey().isEmpty());
        }
        out.put("presets", LlmSettings.PRESETS);
        return out;
    }
}
