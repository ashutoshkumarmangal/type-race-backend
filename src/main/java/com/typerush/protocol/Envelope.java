package com.typerush.protocol;

/** Wire envelope used in both directions: {@code {"type": "...", "data": { ... }}}. */
public record Envelope<T>(String type, T data) {

    public static <T> Envelope<T> of(String type, T data) {
        return new Envelope<>(type, data);
    }
}