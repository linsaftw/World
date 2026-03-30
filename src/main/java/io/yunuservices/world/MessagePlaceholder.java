package io.yunuservices.world;

import java.util.Objects;

public record MessagePlaceholder(String name, String value) {

    public static MessagePlaceholder of(final String name, final Object value) {
        return new MessagePlaceholder(name, Objects.toString(value, ""));
    }
}
