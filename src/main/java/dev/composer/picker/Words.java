package dev.composer.picker;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Простое сравнение русских слов по общему началу, чтобы «финансовый» находил «финансы». */
final class Words {

    private Words() {
    }

    static List<String> split(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\d-]+"))
                .filter(w -> !w.isBlank())
                .toList();
    }

    static String normalize(String text) {
        return String.join(" ", split(text));
    }

    /** true, если у слов совпадает начало: не короче 4 букв (или 3, если одно из слов совсем короткое). */
    static boolean similar(String a, String b) {
        int p = 0;
        int max = Math.min(a.length(), b.length());
        while (p < max && a.charAt(p) == b.charAt(p)) {
            p++;
        }
        return p >= 4 || (p >= 3 && max <= 4);
    }
}
