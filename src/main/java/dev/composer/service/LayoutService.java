package dev.composer.service;

import dev.composer.model.Asset;
import dev.composer.model.Item;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Расставляет выбранные объекты на холсте 1200×630. Три шаблона повторяют примеры из макета:
 * «Собранно», «Под заголовок» (верхняя часть свободна) и «Со смещением» (объекты справа).
 */
@Service
public class LayoutService {

    /** Область, куда ставятся объекты: левая граница, ширина, центр по вертикали, смещение «зигзагом», предельный размер. */
    private record Zone(double x, double width, double cy, double zigzag, double maxDim) {
    }

    public static final Map<String, String> LAYOUTS = new LinkedHashMap<>();

    static {
        LAYOUTS.put("tight", "Собранно");
        LAYOUTS.put("title", "Под заголовок");
        LAYOUTS.put("offset", "Со смещением");
    }

    private static final double BACKGROUND_SCALE = 1.3;

    private final LibraryService library;

    public LayoutService(LibraryService library) {
        this.library = library;
    }

    public List<Item> arrange(List<String> ids, String size, String layout, boolean withBackground) {
        Zone zone = switch (layout) {
            case "tight" -> new Zone(150, 900, 315, 35, 380);
            case "title" -> new Zone(60, 1080, 445, 25, 300);
            case "offset" -> new Zone(430, 710, 315, 80, 400);
            default -> throw new IllegalArgumentException("Неизвестный шаблон: " + layout);
        };
        double target = Math.min("L".equals(size) ? 330 : 210, zone.maxDim());
        double slot = zone.width() / ids.size();

        List<Item> front = new ArrayList<>();
        List<Item> back = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Asset a = library.asset(size, ids.get(i), "main");
            double scale = Math.min(target, slot * 1.15) / Math.max(a.width(), a.height());
            double cx = zone.x() + slot * (i + 0.5);
            double cy = zone.cy() + (i % 2 == 0 ? -zone.zigzag() : zone.zigzag());
            front.add(new Item(ids.get(i), "main", cx, cy, ((i % 3) - 1) * 4.0, scale));
            if (withBackground && i < (ids.size() + 1) / 2) {
                double dx = i % 2 == 0 ? -70 : 70;
                back.add(new Item(ids.get(i), "bg", cx + dx, cy + 25, i % 2 == 0 ? -8 : 8, scale * BACKGROUND_SCALE));
            }
        }
        List<Item> all = new ArrayList<>(back);
        all.addAll(front);
        return all;
    }
}
