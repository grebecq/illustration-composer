package dev.composer.service;

import dev.composer.model.Asset;
import dev.composer.model.Composition;
import dev.composer.model.Item;
import dev.composer.model.Palette;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** Собирает итоговый SVG: фон холста и по одному {@code <g transform>} на объект. Пути объектов не меняются. */
@Service
public class SvgRenderer {

    public static final int WIDTH = 1200;
    public static final int HEIGHT = 630;
    private static final int MAX_ITEMS = 100;

    private final LibraryService library;
    private final PaletteService palettes;

    public SvgRenderer(LibraryService library, PaletteService palettes) {
        this.library = library;
        this.palettes = palettes;
    }

    public String render(Composition c) {
        validate(c);
        Palette palette = palettes.get(c.palette());
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(WIDTH)
                .append("\" height=\"").append(HEIGHT)
                .append("\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(HEIGHT).append("\">\n");
        sb.append("<rect width=\"").append(WIDTH).append("\" height=\"").append(HEIGHT)
                .append("\" fill=\"").append(palette.bgLight()).append("\"/>\n");
        for (Item it : c.items()) {
            Asset a = library.asset(c.size(), it.objectId(), it.variant());
            sb.append("<g transform=\"").append(transform(it, a)).append("\" fill=\"none\">")
                    .append(palettes.recolor(a.inner(), palette))
                    .append("</g>\n");
        }
        return sb.append("</svg>\n").toString();
    }

    /** Одна иллюстрация целиком, в выбранной палитре. */
    public String renderAsset(String size, String id, String variant, Palette palette) {
        Asset a = library.asset(size, id, variant);
        return String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%s\" height=\"%s\" viewBox=\"0 0 %s %s\" fill=\"none\">%s</svg>",
                num(a.width()), num(a.height()), num(a.width()), num(a.height()),
                palettes.recolor(a.inner(), palette));
    }

    private static String transform(Item it, Asset a) {
        return String.format(Locale.ROOT, "translate(%s %s) rotate(%s) scale(%s) translate(%s %s)",
                num(it.x()), num(it.y()), num(it.rotation()), num(it.scale()),
                num(-a.width() / 2), num(-a.height() / 2));
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private void validate(Composition c) {
        if (c == null || c.items() == null || c.size() == null) {
            throw new IllegalArgumentException("Пустая композиция");
        }
        if (!LibraryService.SIZES.contains(c.size())) {
            throw new IllegalArgumentException("Размер должен быть L или S");
        }
        if (c.items().size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Слишком много объектов (максимум " + MAX_ITEMS + ")");
        }
        for (Item it : c.items()) {
            if (!Double.isFinite(it.x()) || !Double.isFinite(it.y())
                    || !Double.isFinite(it.rotation()) || !Double.isFinite(it.scale())
                    || it.scale() < 0.01 || it.scale() > 20) {
                throw new IllegalArgumentException("Некорректные координаты или масштаб у " + it.objectId());
            }
        }
    }
}
