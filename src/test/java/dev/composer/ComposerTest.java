package dev.composer;

import dev.composer.model.Asset;
import dev.composer.model.ComposeRequest;
import dev.composer.model.ComposeResult;
import dev.composer.model.Composition;
import dev.composer.model.Item;
import dev.composer.model.Palette;
import dev.composer.picker.NamePicker;
import dev.composer.picker.TagPicker;
import dev.composer.service.ComposeService;
import dev.composer.service.LibraryService;
import dev.composer.service.PaletteService;
import dev.composer.service.SvgRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "composer.llm.autodetect=false")
class ComposerTest {

    @Autowired LibraryService library;
    @Autowired PaletteService palettes;
    @Autowired SvgRenderer renderer;
    @Autowired ComposeService composer;
    @Autowired TagPicker tags;
    @Autowired NamePicker names;
    @Autowired dev.composer.service.LlmSettings llmSettings;

    @Test
    void libraryHasAllObjectsInBothSizesAndVariants() {
        assertEquals(9, library.objects().size());
        library.objects().forEach(o -> LibraryService.SIZES.forEach(s -> LibraryService.VARIANTS.forEach(v ->
                assertTrue(library.asset(s, o.id(), v).width() > 0))));
    }

    @Test
    void renderKeepsOriginalPathsAndRecolors() {
        Asset book = library.asset("L", "book", "main");
        String firstPath = book.inner().lines().filter(l -> l.startsWith("<path")).findFirst().orElseThrow();
        Composition c = new Composition("L", "teal", List.of(new Item("book", "main", 600, 315, 0, 0.3)));
        String svg = renderer.render(c);

        assertTrue(svg.contains("<g transform=\"translate(600.00 315.00) rotate(0.00) scale(0.30)"));
        // d="..." не изменился: меняются только цвета
        String d = firstPath.substring(firstPath.indexOf(" d=\""), firstPath.indexOf("\"", firstPath.indexOf(" d=\"") + 5) + 1);
        assertTrue(svg.contains(d));
        assertFalse(svg.contains("#F9A3AA"), "розовый должен замениться цветом палитры");
        assertTrue(svg.contains("#6D7BFC"));
        assertTrue(svg.contains("fill=\"#BEEBEB\"/>"), "фон холста — светлый цвет фона палитры");
    }

    @Test
    void backgroundObjectNeverGetsObjectColors() {
        Palette teal = palettes.get("teal");
        String svg = renderer.renderAsset("L", "lamp", "bg", teal);
        assertFalse(svg.contains(teal.objA()) || svg.contains(teal.objB()) || svg.contains(teal.objC()));
    }

    @Test
    void tagPickerUnderstandsMetaphor() {
        List<String> ids = tags.pick("Рост финансов и инвестиции", 3);
        assertEquals("coin", ids.get(0));
        assertTrue(ids.contains("wallet"));
    }

    @Test
    void namePickerFindsInflectedNamesAndReportsUnknown() {
        NamePicker.Result r = names.pick("книга, лампа и телефоны, ноутбук");
        assertEquals(List.of("book", "lamp", "phone"), r.ids());
        assertEquals(List.of("ноутбук"), r.unknown());
    }

    @Test
    void composeByNamesProducesBackgroundAndMainItems() {
        ComposeResult r = composer.compose(new ComposeRequest("names", "книга, лампа, пазлы", "S", "orange", "title", true));
        assertEquals("names", r.source());
        assertEquals(List.of("book", "lamp", "puzzle"), r.picked());
        long bg = r.composition().items().stream().filter(i -> i.variant().equals("bg")).count();
        long main = r.composition().items().stream().filter(i -> i.variant().equals("main")).count();
        assertEquals(2, bg);
        assertEquals(3, main);
        assertEquals("bg", r.composition().items().get(0).variant(), "фон лежит под главными объектами");
    }

    @Test
    void composeByMetaphorFallsBackToTagsWithoutApiKey() {
        llmSettings.disconnect();
        ComposeResult r = composer.compose(new ComposeRequest("metaphor", "обучение", null, null, null, null));
        assertEquals("tags", r.source());
        assertTrue(r.picked().contains("book"));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> palettes.add(new Palette(null, "x",
                "#000000", "#000000", "#000000", "#000000", "#000000", "\"><script>")));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(
                new Composition("L", "purple", List.of(new Item("book", "main", 0, 0, 0, Double.NaN)))));
        assertThrows(IllegalArgumentException.class, () -> composer.compose(new ComposeRequest("names", "  ", null, null, null, null)));
    }
}
