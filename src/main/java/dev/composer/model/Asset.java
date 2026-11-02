package dev.composer.model;

/** SVG-файл, разобранный на размеры и содержимое между тегами {@code <svg>}. Содержимое хранится без изменений. */
public record Asset(double width, double height, String inner) {
}
