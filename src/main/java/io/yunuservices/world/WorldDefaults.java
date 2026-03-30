package io.yunuservices.world;

import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

public record WorldDefaults(World.Environment environment, boolean generateStructures, boolean hardcore) {

    public static WorldDefaults fromConfig(final FileConfiguration config) {
        final String environmentName = config.getString("defaults.environment", World.Environment.NORMAL.name());
        final World.Environment environment;
        try {
            environment = World.Environment.valueOf(environmentName.toUpperCase());
        } catch (final IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid defaults.environment value: " + environmentName, ex);
        }

        return new WorldDefaults(
            environment,
            config.getBoolean("defaults.generate-structures", true),
            config.getBoolean("defaults.hardcore", false)
        );
    }
}
