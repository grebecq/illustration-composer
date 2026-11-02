package dev.composer.web;

import dev.composer.model.Asset;
import dev.composer.model.ComposeRequest;
import dev.composer.model.ComposeResult;
import dev.composer.model.Composition;
import dev.composer.model.Palette;
import dev.composer.service.ComposeService;
import dev.composer.service.LayoutService;
import dev.composer.service.LibraryService;
import dev.composer.service.PaletteService;
import dev.composer.service.SvgRenderer;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final MediaType SVG = MediaType.parseMediaType("image/svg+xml");

    private final LibraryService library;
    private final PaletteService palettes;
    private final ComposeService composer;
    private final SvgRenderer renderer;

    public ApiController(LibraryService library, PaletteService palettes, ComposeService composer, SvgRenderer renderer) {
        this.library = library;
        this.palettes = palettes;
        this.composer = composer;
        this.renderer = renderer;
    }

    /** Всё, что нужно странице при загрузке: объекты, палитры, шаблоны и размеры исходных SVG. */
    @GetMapping("/library")
    public Map<String, Object> library() {
        Map<String, Object> dims = new LinkedHashMap<>();
        for (String size : LibraryService.SIZES) {
            Map<String, Object> bySize = new LinkedHashMap<>();
            library.objects().forEach(o -> {
                Asset a = library.asset(size, o.id(), "main");
                bySize.put(o.id(), Map.of("w", a.width(), "h", a.height()));
            });
            dims.put(size, bySize);
        }
        List<Map<String, String>> layouts = LayoutService.LAYOUTS.entrySet().stream()
                .map(e -> Map.of("id", e.getKey(), "name", e.getValue())).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("objects", library.objects());
        out.put("palettes", palettes.all());
        out.put("layouts", layouts);
        out.put("sizes", LibraryService.SIZES);
        out.put("canvas", Map.of("w", SvgRenderer.WIDTH, "h", SvgRenderer.HEIGHT));
        out.put("dims", dims);
        out.put("llm", composer.llmAvailable());
        return out;
    }

    @GetMapping("/asset/{size}/{id}/{variant}")
    public ResponseEntity<String> asset(@PathVariable String size, @PathVariable String id, @PathVariable String variant,
                                        @RequestParam(required = false) String palette) {
        String svg = renderer.renderAsset(size, id, variant, palettes.get(palette));
        return ResponseEntity.ok().contentType(SVG).body(svg);
    }

    @PostMapping("/palettes")
    public Palette addPalette(@RequestBody Palette palette) {
        return palettes.add(palette);
    }

    @PostMapping("/compose")
    public ComposeResult compose(@RequestBody ComposeRequest request) {
        return composer.compose(request);
    }

    @PostMapping("/render")
    public ResponseEntity<String> render(@RequestBody Composition composition) {
        return ResponseEntity.ok().contentType(SVG).body(renderer.render(composition));
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody Composition composition) {
        byte[] body = renderer.render(composition).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(SVG)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("composition.svg").build().toString())
                .body(body);
    }
}
