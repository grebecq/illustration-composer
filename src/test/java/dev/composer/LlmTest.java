package dev.composer;

import com.sun.net.httpserver.HttpServer;
import dev.composer.model.ComposeRequest;
import dev.composer.model.ComposeResult;
import dev.composer.picker.LlmException;
import dev.composer.picker.LlmPicker;
import dev.composer.service.ComposeService;
import dev.composer.service.LlmSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет оба протокола ИИ на заглушке-сервере: реальных ключей и сети не нужно. */
@SpringBootTest(properties = "composer.llm.autodetect=false")
class LlmTest {

    @Autowired LlmSettings settings;
    @Autowired LlmPicker picker;
    @Autowired ComposeService composer;

    private HttpServer server;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastKey = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        settings.disconnect();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        settings.disconnect();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void answer(String path, int status, String body) {
        server.createContext(path, ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            ex.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @Test
    void openAiCompatibleServerWithoutKey() throws Exception {
        answer("/v1/chat/completions", 200,
                "{\"choices\":[{\"message\":{\"content\":\"Вот: [\\\"coin\\\", \\\"wallet\\\", \\\"выдуманный\\\"]\"}}]}");
        settings.save("custom", base() + "/v1", "model", "");

        assertEquals(List.of("coin", "wallet"), picker.pickOrThrow("деньги", 3));
        assertNull(lastAuth.get(), "для локального сервера без ключа заголовок не отправляется");
    }

    @Test
    void openAiCompatibleServerWithKey() throws Exception {
        answer("/v1/chat/completions", 200, "{\"choices\":[{\"message\":{\"content\":\"[\\\"book\\\"]\"}}]}");
        settings.save("custom", base() + "/v1", "model", "sk-test");

        assertEquals(List.of("book"), picker.pickOrThrow("учёба", 3));
        assertEquals("Bearer sk-test", lastAuth.get());
    }

    @Test
    void anthropicProtocol() throws Exception {
        answer("/v1/messages", 200, "{\"content\":[{\"type\":\"text\",\"text\":\"[\\\"lamp\\\"]\"}]}");
        settings.save("anthropic", base(), "claude-haiku-5-5", "key-1");

        assertEquals(List.of("lamp"), picker.pickOrThrow("идея", 3));
        assertEquals("key-1", lastKey.get());
    }

    @Test
    void modelAnsweringInWordsStillWorks() throws Exception {
        answer("/v1/chat/completions", 200,
                "{\"choices\":[{\"message\":{\"content\":\"Подойдут lamp и book, а ещё phone.\"}}]}");
        settings.save("custom", base() + "/v1", "model", "");

        assertEquals(List.of("lamp", "book", "phone"), picker.pickOrThrow("идея", 5));
    }

    @Test
    void explainsWhyItFailed() {
        answer("/v1/chat/completions", 401, "{\"error\":\"bad key\"}");
        settings.save("custom", base() + "/v1", "model", "wrong");
        LlmException denied = assertThrows(LlmException.class, () -> picker.pickOrThrow("идея", 3));
        assertTrue(denied.getMessage().contains("отклонён"), denied.getMessage());

        server.stop(0);
        LlmException down = assertThrows(LlmException.class, () -> picker.pickOrThrow("идея", 3));
        assertTrue(down.getMessage().contains("нет связи"), down.getMessage());
    }

    @Test
    void composeFallsBackToTagsAndReportsReason() {
        answer("/v1/chat/completions", 404, "{}");
        settings.save("custom", base() + "/v1", "missing", "");

        ComposeResult r = composer.compose(new ComposeRequest("metaphor", "обучение", null, null, null, null));
        assertEquals("tags", r.source());
        assertTrue(r.llmError().contains("404"), r.llmError());
        assertTrue(r.picked().contains("book"));
    }

    @Test
    void composeUsesModelWhenItAnswers() {
        answer("/v1/chat/completions", 200, "{\"choices\":[{\"message\":{\"content\":\"[\\\"phone\\\",\\\"puzzle\\\"]\"}}]}");
        settings.save("custom", base() + "/v1", "m", "");

        ComposeResult r = composer.compose(new ComposeRequest("metaphor", "что угодно", null, null, null, null));
        assertEquals("llm", r.source());
        assertEquals(List.of("phone", "puzzle"), r.picked());
        assertNull(r.llmError());
    }

    @Test
    void validatesSettings() {
        assertThrows(IllegalArgumentException.class, () -> settings.save("openrouter", null, null, ""));
        assertThrows(IllegalArgumentException.class, () -> settings.save("custom", "file:///etc/passwd", "m", ""));
        assertThrows(IllegalArgumentException.class, () -> settings.save("custom", base(), "m", "bad key\nX: 1"));
        assertThrows(IllegalArgumentException.class, () -> settings.save("nope", null, null, null));
    }
}
