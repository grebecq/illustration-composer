package dev.composer.picker;

import dev.composer.model.Illustration;
import dev.composer.service.LibraryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Сценарий 2: человек сам перечисляет названия компонентов, например «книга, лампа и телефон». */
@Component
public class NamePicker {

    private final LibraryService library;

    public NamePicker(LibraryService library) {
        this.library = library;
    }

    public record Result(List<String> ids, List<String> unknown) {
    }

    public Result pick(String query) {
        List<String> ids = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String part : query.split("[,;\\n]+|\\sи\\s")) {
            String name = Words.normalize(part);
            if (name.isBlank()) {
                continue;
            }
            Illustration found = find(name);
            if (found == null) {
                unknown.add(part.strip());
            } else if (!ids.contains(found.id())) {
                ids.add(found.id());
            }
        }
        return new Result(ids, unknown);
    }

    private Illustration find(String name) {
        for (Illustration o : library.objects()) {
            if (name.equals(o.id()) || Words.similar(Words.normalize(o.name()), name)) {
                return o;
            }
        }
        return null;
    }
}
