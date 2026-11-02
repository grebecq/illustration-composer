package dev.composer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.composer.model.Asset;
import dev.composer.model.Illustration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Библиотека иллюстраций. Файлы {@code illustrations/{размер}/{id}-{вариант}.svg} читаются один раз при старте;
 * содержимое SVG после этого не изменяется — композиции только оборачивают его в {@code <g>} и перекрашивают.
 */
@Service
public class LibraryService {

    public static final List<String> SIZES = List.of("L", "S");
    public static final List<String> VARIANTS = List.of("main", "bg");

    private static final Pattern SVG = Pattern.compile("(?s)^\\s*<svg([^>]*)>(.*)</svg>\\s*$");
    private static final Pattern WIDTH = Pattern.compile("\\swidth=\"([0-9.]+)\"");
    private static final Pattern HEIGHT = Pattern.compile("\\sheight=\"([0-9.]+)\"");

    private final List<Illustration> objects;
    private final Map<String, Asset> assets = new HashMap<>();

    public LibraryService(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource("library.json").getInputStream()) {
            objects = mapper.readValue(in, new TypeReference<>() {});
        }
        for (Illustration o : objects) {
            for (String size : SIZES) {
                for (String variant : VARIANTS) {
                    assets.put(key(size, o.id(), variant), load(size, o.id(), variant));
                }
            }
        }
    }

    public List<Illustration> objects() {
        return objects;
    }

    public Illustration object(String id) {
        return objects.stream().filter(o -> o.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Неизвестный объект: " + id));
    }

    public Asset asset(String size, String id, String variant) {
        Asset a = assets.get(key(size, id, variant));
        if (a == null) {
            throw new IllegalArgumentException("Нет иллюстрации: " + size + "/" + id + "/" + variant);
        }
        return a;
    }

    private static String key(String size, String id, String variant) {
        return size + "/" + id + "/" + variant;
    }

    private static Asset load(String size, String id, String variant) throws IOException {
        String path = "illustrations/" + size + "/" + id + "-" + variant + ".svg";
        String text;
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = SVG.matcher(text);
        if (!m.matches()) {
            throw new IllegalStateException("Не SVG: " + path);
        }
        return new Asset(number(WIDTH, m.group(1), path), number(HEIGHT, m.group(1), path), m.group(2));
    }

    private static double number(Pattern p, String attrs, String path) {
        Matcher m = p.matcher(attrs);
        if (!m.find()) {
            throw new IllegalStateException("Нет размеров в " + path);
        }
        return Double.parseDouble(m.group(1));
    }
}
