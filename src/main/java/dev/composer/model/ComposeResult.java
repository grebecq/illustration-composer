package dev.composer.model;

import java.util.List;

/**
 * {@code source}: {@code llm}, {@code tags} или {@code names} — чем подобраны объекты.
 * {@code llmError} — почему не сработал ИИ, если он подключён, но пришлось подбирать по тегам (иначе {@code null}).
 */
public record ComposeResult(Composition composition, List<String> picked, String source, String llmError) {
}
