package io.yunuservices.world;

public record MessagesReloadResult(boolean success, String reason) {

    public static MessagesReloadResult loaded() {
        return new MessagesReloadResult(true, "");
    }

    public static MessagesReloadResult invalid(final String reason) {
        return new MessagesReloadResult(false, reason);
    }
}
