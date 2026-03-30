package io.yunuservices.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

public final class WorldsFileStore {

    private final Plugin plugin;
    private final Path file;
    private final Object lock = new Object();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private YamlConfiguration configuration;
    private String pendingSnapshot;

    public WorldsFileStore(final Plugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("worlds.yml");
        this.reload();
    }

    public void reload() {
        synchronized (this.lock) {
            try {
                Files.createDirectories(this.plugin.getDataFolder().toPath());
            } catch (final IOException ex) {
                throw new IllegalStateException("Failed to create the plugin data folder.", ex);
            }

            this.configuration = Files.exists(this.file)
                ? YamlConfiguration.loadConfiguration(this.file.toFile())
                : new YamlConfiguration();

            if (!this.configuration.isConfigurationSection("worlds")) {
                this.configuration.createSection("worlds");
                this.persistAsync();
            }
        }
    }

    public boolean isTracked(final String worldName) {
        synchronized (this.lock) {
            return this.configuration.isConfigurationSection(this.path(worldName));
        }
    }

    public List<String> trackedWorldNames() {
        synchronized (this.lock) {
            final ConfigurationSection section = this.configuration.getConfigurationSection("worlds");
            if (section == null) {
                return List.of();
            }

            final List<String> names = new ArrayList<>();
            for (final String key : section.getKeys(false)) {
                names.add(section.getString(key + ".name", key));
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }
    }

    public World.Environment environment(final String worldName) {
        synchronized (this.lock) {
            final String environmentName = this.configuration.getString(this.path(worldName) + ".environment");
            if (environmentName == null || environmentName.isBlank()) {
                return null;
            }

            try {
                return World.Environment.valueOf(environmentName.toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException ex) {
                this.plugin.getLogger().warning("Stored invalid environment '" + environmentName + "' for world '" + worldName + "'.");
                return null;
            }
        }
    }

    public void rememberEnvironment(final String worldName, final World.Environment environment) {
        synchronized (this.lock) {
            final String path = this.path(worldName);
            final String value = environment.name();
            if (value.equals(this.configuration.getString(path + ".environment"))
                && worldName.equals(this.configuration.getString(path + ".name"))) {
                return;
            }

            this.configuration.set(path + ".name", worldName);
            this.configuration.set(path + ".environment", value);
            this.persistAsync();
        }
    }

    public void rememberSpawn(final String worldName, final Location location) {
        synchronized (this.lock) {
            final String path = this.path(worldName);
            if (this.hasSameSpawn(path, worldName, location)) {
                return;
            }
            this.configuration.set(path + ".name", worldName);
            this.configuration.set(path + ".spawn.x", location.getX());
            this.configuration.set(path + ".spawn.y", location.getY());
            this.configuration.set(path + ".spawn.z", location.getZ());
            this.configuration.set(path + ".spawn.yaw", location.getYaw());
            this.configuration.set(path + ".spawn.pitch", location.getPitch());
            this.persistAsync();
        }
    }

    public void trackWorld(final String worldName, final World.Environment environment, final Location spawn) {
        synchronized (this.lock) {
            final String path = this.path(worldName);
            this.configuration.set(path + ".name", worldName);
            if (environment != null) {
                this.configuration.set(path + ".environment", environment.name());
            }
            if (spawn != null) {
                this.configuration.set(path + ".spawn.x", spawn.getX());
                this.configuration.set(path + ".spawn.y", spawn.getY());
                this.configuration.set(path + ".spawn.z", spawn.getZ());
                this.configuration.set(path + ".spawn.yaw", spawn.getYaw());
                this.configuration.set(path + ".spawn.pitch", spawn.getPitch());
            }
            this.persistAsync();
        }
    }

    public void copyWorldSettings(final String sourceWorldName, final String targetWorldName, final World.Environment fallbackEnvironment) {
        synchronized (this.lock) {
            final String sourcePath = this.path(sourceWorldName);
            final String targetPath = this.path(targetWorldName);

            this.configuration.set(targetPath + ".name", targetWorldName);

            final String environment = this.configuration.getString(sourcePath + ".environment");
            if (environment != null && !environment.isBlank()) {
                this.configuration.set(targetPath + ".environment", environment);
            } else if (fallbackEnvironment != null) {
                this.configuration.set(targetPath + ".environment", fallbackEnvironment.name());
            }

            if (this.configuration.isConfigurationSection(sourcePath + ".spawn")) {
                this.configuration.set(targetPath + ".spawn.x", this.configuration.getDouble(sourcePath + ".spawn.x"));
                this.configuration.set(targetPath + ".spawn.y", this.configuration.getDouble(sourcePath + ".spawn.y"));
                this.configuration.set(targetPath + ".spawn.z", this.configuration.getDouble(sourcePath + ".spawn.z"));
                this.configuration.set(targetPath + ".spawn.yaw", (float) this.configuration.getDouble(sourcePath + ".spawn.yaw"));
                this.configuration.set(targetPath + ".spawn.pitch", (float) this.configuration.getDouble(sourcePath + ".spawn.pitch"));
            }

            this.copySection(sourcePath + ".portals", targetPath + ".portals");

            this.persistAsync();
        }
    }

    public String portalWorld(final String worldName, final PortalKind portalKind) {
        synchronized (this.lock) {
            return this.configuration.getString(this.portalPath(worldName, portalKind) + ".world");
        }
    }

    public ServerTransferTarget portalTransfer(final String worldName, final PortalKind portalKind) {
        synchronized (this.lock) {
            final String value = this.configuration.getString(this.portalPath(worldName, portalKind) + ".transfer");
            if (value == null || value.isBlank()) {
                return null;
            }

            try {
                return ServerTransferTarget.parse(value);
            } catch (final IllegalArgumentException ex) {
                this.plugin.getLogger().warning(
                    "Stored invalid transfer target '" + value + "' for world '" + worldName + "' and portal " + portalKind.displayName() + "."
                );
                return null;
            }
        }
    }

    public String portalWorldSummary(final String worldName, final PortalKind portalKind) {
        final String value = this.portalWorld(worldName, portalKind);
        return value == null || value.isBlank() ? "-" : value;
    }

    public String portalTransferSummary(final String worldName, final PortalKind portalKind) {
        final ServerTransferTarget transferTarget = this.portalTransfer(worldName, portalKind);
        return transferTarget == null ? "-" : transferTarget.asConfigValue();
    }

    public void rememberPortalWorld(final String worldName, final PortalKind portalKind, final String targetWorldName) {
        synchronized (this.lock) {
            final String path = this.portalPath(worldName, portalKind);
            if (targetWorldName.equals(this.configuration.getString(path + ".world"))
                && worldName.equals(this.configuration.getString(this.path(worldName) + ".name"))) {
                return;
            }

            this.configuration.set(this.path(worldName) + ".name", worldName);
            this.configuration.set(path + ".world", targetWorldName);
            this.persistAsync();
        }
    }

    public void clearPortalWorld(final String worldName, final PortalKind portalKind) {
        synchronized (this.lock) {
            final String path = this.portalPath(worldName, portalKind) + ".world";
            if (!this.configuration.contains(path)) {
                return;
            }

            this.configuration.set(path, null);
            this.persistAsync();
        }
    }

    public void rememberPortalTransfer(final String worldName, final PortalKind portalKind, final ServerTransferTarget transferTarget) {
        synchronized (this.lock) {
            final String path = this.portalPath(worldName, portalKind);
            final String value = transferTarget.asConfigValue();
            if (value.equals(this.configuration.getString(path + ".transfer"))
                && worldName.equals(this.configuration.getString(this.path(worldName) + ".name"))) {
                return;
            }

            this.configuration.set(this.path(worldName) + ".name", worldName);
            this.configuration.set(path + ".transfer", value);
            this.persistAsync();
        }
    }

    public void clearPortalTransfer(final String worldName, final PortalKind portalKind) {
        synchronized (this.lock) {
            final String path = this.portalPath(worldName, portalKind) + ".transfer";
            if (!this.configuration.contains(path)) {
                return;
            }

            this.configuration.set(path, null);
            this.persistAsync();
        }
    }

    public Location resolveSpawn(final World world) {
        synchronized (this.lock) {
            final String path = this.path(world.getName()) + ".spawn";
            final Location fallback = world.getSpawnLocation();
            if (!this.configuration.isConfigurationSection(path)) {
                return fallback;
            }

            return new Location(
                world,
                this.configuration.getDouble(path + ".x", fallback.getX()),
                this.configuration.getDouble(path + ".y", fallback.getY()),
                this.configuration.getDouble(path + ".z", fallback.getZ()),
                (float) this.configuration.getDouble(path + ".yaw", fallback.getYaw()),
                (float) this.configuration.getDouble(path + ".pitch", fallback.getPitch())
            );
        }
    }

    public String spawnSummary(final String worldName) {
        synchronized (this.lock) {
            final String path = this.path(worldName) + ".spawn";
            if (!this.configuration.isConfigurationSection(path)) {
                return "-";
            }

            return String.format(
                Locale.US,
                "x=%.2f, y=%.2f, z=%.2f, yaw=%.2f, pitch=%.2f",
                this.configuration.getDouble(path + ".x"),
                this.configuration.getDouble(path + ".y"),
                this.configuration.getDouble(path + ".z"),
                this.configuration.getDouble(path + ".yaw"),
                this.configuration.getDouble(path + ".pitch")
            );
        }
    }

    public void removeWorld(final String worldName) {
        synchronized (this.lock) {
            final String path = this.path(worldName);
            if (!this.configuration.contains(path)) {
                return;
            }

            this.configuration.set(path, null);
            this.persistAsync();
        }
    }

    private String path(final String worldName) {
        return "worlds." + worldName.toLowerCase(Locale.ROOT);
    }

    private String portalPath(final String worldName, final PortalKind portalKind) {
        return this.path(worldName) + ".portals." + portalKind.configKey();
    }

    private void persistAsync() {
        synchronized (this.lock) {
            this.pendingSnapshot = this.configuration.saveToString();
        }
        this.scheduleFlush();
    }

    private void scheduleFlush() {
        if (!this.flushScheduled.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            try {
                for (;;) {
                    final String snapshot = this.takePendingSnapshot();
                    if (snapshot == null) {
                        break;
                    }

                    try {
                        Files.writeString(
                            this.file,
                            snapshot,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE
                        );
                    } catch (final IOException ex) {
                        this.plugin.getLogger().warning("Failed to persist worlds.yml: " + ex.getMessage());
                    }
                }
            } finally {
                this.flushScheduled.set(false);
                if (this.hasPendingSnapshot()) {
                    this.scheduleFlush();
                }
            }
        });
    }

    private String takePendingSnapshot() {
        synchronized (this.lock) {
            final String snapshot = this.pendingSnapshot;
            this.pendingSnapshot = null;
            return snapshot;
        }
    }

    private boolean hasPendingSnapshot() {
        synchronized (this.lock) {
            return this.pendingSnapshot != null;
        }
    }

    private boolean hasSameSpawn(final String path, final String worldName, final Location location) {
        if (!this.configuration.isConfigurationSection(path + ".spawn")) {
            return false;
        }
        if (!worldName.equals(this.configuration.getString(path + ".name"))) {
            return false;
        }
        return Double.compare(this.configuration.getDouble(path + ".spawn.x"), location.getX()) == 0
            && Double.compare(this.configuration.getDouble(path + ".spawn.y"), location.getY()) == 0
            && Double.compare(this.configuration.getDouble(path + ".spawn.z"), location.getZ()) == 0
            && Float.compare((float) this.configuration.getDouble(path + ".spawn.yaw"), location.getYaw()) == 0
            && Float.compare((float) this.configuration.getDouble(path + ".spawn.pitch"), location.getPitch()) == 0;
    }

    private void copySection(final String sourcePath, final String targetPath) {
        this.configuration.set(targetPath, null);
        final ConfigurationSection section = this.configuration.getConfigurationSection(sourcePath);
        if (section == null) {
            return;
        }

        for (final String key : section.getKeys(true)) {
            if (section.isConfigurationSection(key)) {
                continue;
            }
            this.configuration.set(targetPath + "." + key, section.get(key));
        }
    }
}
