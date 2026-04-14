package io.yunuservices.world;

import java.nio.file.Path;
import java.util.Optional;

public final class WorldNameRules {

    private static final int MAX_LENGTH = 64;

    private WorldNameRules() {
    }

    public static Optional<String> normalize(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }

        final String name = raw.strip();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        if (name.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (name.equals(".") || name.equals("..")) {
            return Optional.empty();
        }

        for (int i = 0; i < name.length(); i++) {
            final char current = name.charAt(i);
            if (current == '/' || current == '\\' || current == ':') {
                return Optional.empty();
            }
            if (Character.isISOControl(current)) {
                return Optional.empty();
            }
        }

        return Optional.of(name);
    }

    public static Path resolveInsideWorldContainer(final Path worldContainer, final String rawName) {
        final Path absoluteContainer = worldContainer.toAbsolutePath().normalize();
        final String normalizedName = normalize(rawName)
            .orElseThrow(() -> new IllegalArgumentException("Invalid managed world name: " + rawName));

        final Path resolved = absoluteContainer.resolve(normalizedName).toAbsolutePath().normalize();
        if (!resolved.startsWith(absoluteContainer)) {
            throw new IllegalArgumentException("Refusing to use a path outside the world container: " + normalizedName);
        }

        return resolved;
    }
}