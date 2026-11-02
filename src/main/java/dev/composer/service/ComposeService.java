package dev.composer.service;

import dev.composer.model.ComposeRequest;
import dev.composer.model.ComposeResult;
import dev.composer.model.Composition;
import dev.composer.picker.LlmException;
import dev.composer.picker.LlmPicker;
import dev.composer.picker.NamePicker;
import dev.composer.picker.TagPicker;
import org.springframework.stereotype.Service;

import java.util.List;

/** Два сценария сборки: по метафоре или заголовку (LLM, при неудаче теги) и по названиям компонентов. */
@Service
public class ComposeService {

    private static final int MAX_OBJECTS = 5;
    private static final int MAX_QUERY_LENGTH = 300;

    private final LlmPicker llm;
    private final TagPicker tags;
    private final NamePicker names;
    private final LayoutService layout;
    private final PaletteService palettes;

    public ComposeService(LlmPicker llm, TagPicker tags, NamePicker names, LayoutService layout, PaletteService palettes) {
        this.llm = llm;
        this.tags = tags;
        this.names = names;
        this.layout = layout;
        this.palettes = palettes;
    }

    public boolean llmAvailable() {
        return llm.available();
    }

    public ComposeResult compose(ComposeRequest req) {
        String query = req.query() == null ? "" : req.query().strip();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("Введите метафору, заголовок или названия объектов");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Запрос длиннее " + MAX_QUERY_LENGTH + " символов");
        }
        String size = req.size() == null || req.size().isBlank() ? "L" : req.size();
        if (!LibraryService.SIZES.contains(size)) {
            throw new IllegalArgumentException("Размер должен быть L или S");
        }
        String paletteId = palettes.get(req.palette()).id();
        String layoutId = req.layout() == null || req.layout().isBlank() ? "tight" : req.layout();
        boolean background = req.background() == null || req.background();

        List<String> ids;
        String source;
        String llmError = null;
        if ("names".equals(req.mode())) {
            NamePicker.Result r = names.pick(query);
            if (!r.unknown().isEmpty()) {
                throw new IllegalArgumentException("Не нашёл в библиотеке: " + String.join(", ", r.unknown()));
            }
            ids = r.ids().stream().limit(MAX_OBJECTS).toList();
            source = "names";
        } else {
            ids = List.of();
            if (llm.available()) {
                try {
                    ids = llm.pickOrThrow(query, MAX_OBJECTS);
                } catch (LlmException e) {
                    llmError = e.getMessage();
                }
            }
            source = "llm";
            if (ids.isEmpty()) {
                ids = tags.pick(query, 4);
                source = "tags";
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Не нашёл подходящих объектов. Попробуйте другие слова.");
        }
        Composition composition = new Composition(size, paletteId, layout.arrange(ids, size, layoutId, background));
        return new ComposeResult(composition, ids, source, llmError);
    }
}
