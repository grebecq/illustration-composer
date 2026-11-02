package dev.composer.model;

import java.util.List;

/** Описание объекта библиотеки: имя и теги для подбора. Сами SVG хранятся отдельно. */
public record Illustration(String id, String name, List<String> tags) {
}
