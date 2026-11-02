package dev.composer.model;

/**
 * Один объект на холсте. {@code x, y} — центр объекта, {@code variant} — {@code main} или {@code bg}.
 * Порядок в списке {@link Composition#items()} задаёт порядок слоёв: первый — самый нижний.
 */
public record Item(String objectId, String variant, double x, double y, double rotation, double scale) {
}
