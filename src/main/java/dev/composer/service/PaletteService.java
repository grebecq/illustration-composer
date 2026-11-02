package dev.composer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.composer.model.Palette;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Палитры и перекраска. Исходные иллюстрации нарисованы в сиреневой схеме, поэтому перекраска — это
 * замена пяти известных цветов на цвета выбранной палитры за один проход.
 */
@Service
public class PaletteService {

    public static final String DEFAULT_ID = "teal";

    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Pattern SOURCE_COLOR = Pattern.compile("#(D7CDF5|7864D7|F9ECB0|F9A3AA|FFE3E1)", Pattern.CASE_INSENSITIVE);

    private final Map<String, Palette> palettes = new LinkedHashMap<>();
    private int customCounter = 0;

    public PaletteService(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource("palettes.json").getInputStream()) {
            List<Palette> loaded = mapper.readValue(in, new TypeReference<>() {});
            loaded.forEach(p -> palettes.put(p.id(), p));
        }
    }

    public synchronized List<Palette> all() {
        return new ArrayList<>(palettes.values());
    }

    public synchronized Palette get(String id) {
        Palette p = palettes.get(id == null || id.isBlank() ? DEFAULT_ID : id);
        if (p == null) {
            throw new IllegalArgumentException("Неизвестная палитра: " + id);
        }
        return p;
    }

    /** Добавляет загруженную палитру. Цвета попадают прямо в SVG, поэтому принимаются только вида #RRGGBB. */
    public synchronized Palette add(Palette p) {
        for (String c : List.of(p.bgLight(), p.bgMid(), p.bgDark(), p.objA(), p.objB(), p.objC())) {
            if (c == null || !HEX.matcher(c).matches()) {
                throw new IllegalArgumentException("Цвет должен быть в формате #RRGGBB: " + c);
            }
        }
        String name = p.name() == null || p.name().isBlank() ? "Своя палитра" : p.name().strip();
        if (name.length() > 40) {
            name = name.substring(0, 40);
        }
        Palette saved = new Palette("custom-" + (++customCounter), name,
                p.bgLight(), p.bgMid(), p.bgDark(), p.objA(), p.objB(), p.objC());
        palettes.put(saved.id(), saved);
        return saved;
    }

    public String recolor(String inner, Palette p) {
        Map<String, String> map = Map.of(
                "D7CDF5", p.bgLight(),
                "7864D7", p.bgDark(),
                "F9ECB0", p.objA(),
                "F9A3AA", p.objB(),
                "FFE3E1", p.objC());
        Matcher m = SOURCE_COLOR.matcher(inner);
        return m.replaceAll(r -> Matcher.quoteReplacement(map.get(r.group(1).toUpperCase(Locale.ROOT))));
    }
}
