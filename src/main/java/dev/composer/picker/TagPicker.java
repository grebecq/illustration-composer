package dev.composer.picker;

import dev.composer.model.Illustration;
import dev.composer.service.LibraryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Запасной способ подбора: сравнивает слова запроса с названием и тегами объектов. Работает без сети. */
@Component
public class TagPicker implements ObjectPicker {

    private static final double NAME_MATCH = 4;
    private static final double TAG_EXACT = 3;
    private static final double TAG_SIMILAR = 1.5;

    private final LibraryService library;

    public TagPicker(LibraryService library) {
        this.library = library;
    }

    @Override
    public List<String> pick(String query, int max) {
        List<String> words = Words.split(query).stream().filter(w -> w.length() >= 3).toList();
        record Scored(String id, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Illustration o : library.objects()) {
            double score = 0;
            for (String w : words) {
                score += scoreWord(o, w);
            }
            if (score > 0) {
                scored.add(new Scored(o.id(), score));
            }
        }
        // sort стабильный: при равных баллах порядок библиотеки сохраняется
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(max)
                .map(Scored::id)
                .toList();
    }

    private static double scoreWord(Illustration o, String word) {
        if (Words.similar(Words.normalize(o.name()), word)) {
            return NAME_MATCH;
        }
        double best = 0;
        for (String tag : o.tags()) {
            for (String t : Words.split(tag)) {
                if (t.equals(word)) {
                    best = Math.max(best, TAG_EXACT);
                } else if (Words.similar(t, word)) {
                    best = Math.max(best, TAG_SIMILAR);
                }
            }
        }
        return best;
    }
}
