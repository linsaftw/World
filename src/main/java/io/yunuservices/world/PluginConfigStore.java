package io.yunuservices.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

public final class PluginConfigStore {

    private final Plugin plugin;
    private final Path file;
    private volatile PluginSettings settings;

    public PluginConfigStore(final Plugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("config.yml");
        this.reload();
    }

    public PluginSettings settings() {
        return this.settings;
    }

    public synchronized void reload() {
        try {
            Files.createDirectories(this.plugin.getDataFolder().toPath());
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to create the plugin data folder.", ex);
        }

        final boolean exists = Files.exists(this.file);
        final YamlConfiguration configuration = exists
            ? YamlConfiguration.loadConfiguration(this.file.toFile())
            : new YamlConfiguration();

        boolean changed = !exists;
        changed |= this.setDefault(configuration, "defaults.environment", World.Environment.NORMAL.name());
        changed |= this.setDefault(configuration, "defaults.generate-structures", true);
        changed |= this.setDefault(configuration, "defaults.hardcore", false);
        changed |= this.setDefault(configuration, "commands.unload.save-by-default", true);
        changed |= this.setDefault(configuration, "commands.delete.save-by-default", true);
        changed |= this.setDefault(configuration, "commands.copy.load-copied-world-by-default", true);
        changed |= this.setDefault(configuration, "commands.import.capture-spawn-for-loaded-worlds", true);

        if (changed) {
            this.save(configuration);
        }

        this.settings = new PluginSettings(
            WorldDefaults.fromConfig(configuration),
            configuration.getBoolean("commands.unload.save-by-default", true),
            configuration.getBoolean("commands.delete.save-by-default", true),
            configuration.getBoolean("commands.copy.load-copied-world-by-default", true),
            configuration.getBoolean("commands.import.capture-spawn-for-loaded-worlds", true)
        );
    }

    private boolean setDefault(final YamlConfiguration configuration, final String path, final Object value) {
        if (configuration.contains(path)) {
            return false;
        }
        configuration.set(path, value);
        return true;
    }

    private void save(final YamlConfiguration configuration) {
        try {
            configuration.save(this.file.toFile());
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to save config.yml.", ex);
        }
    }
}
