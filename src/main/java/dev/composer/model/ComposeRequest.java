package dev.composer.model;

/**
 * Запрос на сборку. {@code mode} — {@code metaphor} (текст со смыслом) или {@code names} (список названий через запятую).
 * Пустые поля заменяются значениями по умолчанию.
 */
public record ComposeRequest(String mode, String query, String size, String palette, String layout, Boolean background) {
}
