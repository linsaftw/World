package io.yunuservices.world;

import java.util.List;
import java.util.Locale;
import org.bukkit.event.player.PlayerTeleportEvent;

public enum PortalKind {
    NETHER("nether"),
    END("end");

    private static final List<String> SUGGESTIONS = List.of("NETHER", "END");

    private final String configKey;

    PortalKind(final String configKey) {
        this.configKey = configKey;
    }

    public String configKey() {
        return this.configKey;
    }

    public String displayName() {
        return this.name();
    }

    public static List<String> suggestions() {
        return SUGGESTIONS;
    }

    public static PortalKind fromInput(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "NETHER", "NETHER_PORTAL" -> NETHER;
            case "END", "ENDER", "THE_END", "END_PORTAL" -> END;
            default -> null;
        };
    }

    public static PortalKind fromCause(final PlayerTeleportEvent.TeleportCause cause) {
        return switch (cause) {
            case NETHER_PORTAL -> NETHER;
            case END_PORTAL -> END;
            default -> null;
        };
    }
}
