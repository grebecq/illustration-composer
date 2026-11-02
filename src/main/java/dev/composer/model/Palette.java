package dev.composer.model;

/**
 * Цветовая схема. Цвета фона ({@code bgLight}, {@code bgDark}) используются и в фоновых объектах,
 * цвета объектов ({@code objA..objC}) — только в главных.
 */
public record Palette(String id, String name,
                      String bgLight, String bgMid, String bgDark,
                      String objA, String objB, String objC) {
}
