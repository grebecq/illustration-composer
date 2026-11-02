package dev.composer.picker;

/** Понятная пользователю причина, по которой ИИ не смог подобрать объекты. */
public class LlmException extends Exception {

    public LlmException(String message) {
        super(message);
    }
}
