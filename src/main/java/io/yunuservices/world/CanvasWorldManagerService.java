package io.yunuservices.world;

import io.canvasmc.canvas.WorldUnloadResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class CanvasWorldManagerService implements WorldManagerService {

    private static final long WORLD_DIRECTORY_CACHE_TTL_MILLIS = 1_500L;

    private final Plugin plugin;
    private final Path worldContainer;
    private final PluginConfigStore configStore;
    private final WorldsFileStore worldsFileStore;
    private final MessagesStore messagesStore;
    private volatile DirectorySnapshot directorySnapshot;

    public CanvasWorldManagerService(
        final Plugin plugin,
        final PluginConfigStore configStore,
        final WorldsFileStore worldsFileStore,
        final MessagesStore messagesStore
    ) {
        this.plugin = plugin;
        this.worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        this.configStore = configStore;
        this.worldsFileStore = worldsFileStore;
        this.messagesStore = messagesStore;
        this.directorySnapshot = DirectorySnapshot.empty();
    }

    public void loadTrackedWorldsOnStartup() {
        final List<String> trackedWorlds = this.worldsFileStore.trackedWorldNames();
        if (trackedWorlds.isEmpty()) {
            return;
        }

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (final String trackedWorld : trackedWorlds) {
            final String normalizedName = this.normalizeWorldName(trackedWorld).orElse(null);
            if (normalizedName == null) {
                this.plugin.getLogger().warning("Skipping invalid tracked world name during startup load: " + trackedWorld);
                continue;
            }
            chain = chain.thenCompose(ignored -> this.loadTrackedWorldOnStartup(normalizedName));
        }

        chain.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                this.plugin.getLogger().warning("Tracked world startup loading stopped early: " + this.unwrap(throwable).getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<OperationOutcome<List<WorldDescriptor>>> listWorlds() {
        return this.runAsyncIo(this::readDiskWorldState)
            .thenCompose(diskState -> this.runOnGlobalThread(() -> {
                final Set<String> knownWorlds = new LinkedHashSet<>();
                knownWorlds.addAll(diskState.names());
                knownWorlds.addAll(this.worldsFileStore.trackedWorldNames());
                for (final World loadedWorld : Bukkit.getWorlds()) {
                    knownWorlds.add(loadedWorld.getName());
                }

                final List<WorldDescriptor> worlds = new ArrayList<>();
                for (final String worldName : knownWorlds) {
                    final World loadedWorld = Bukkit.getWorld(worldName);
                    final Path directory = loadedWorld != null
                        ? loadedWorld.getWorldFolder().toPath().toAbsolutePath().normalize()
                        : this.safeResolveWorldPath(worldName);
                    worlds.add(this.describe(worldName, directory, loadedWorld, loadedWorld != null || diskState.names().contains(worldName)));
                }

                worlds.sort(Comparator.comparing(WorldDescriptor::name, String.CASE_INSENSITIVE_ORDER));
                return OperationOutcome.success("Listed " + worlds.size() + " world(s).", List.copyOf(worlds));
            }));
    }

    @Override
    public CompletableFuture<OperationOutcome<WorldDescriptor>> describeWorld(final String name) {
        final String normalizedName = this.normalizeWorldName(name).orElse(null);
        if (normalizedName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(name));
        }

        final Path directory;
        try {
            directory = this.resolveWorldPath(normalizedName);
        } catch (final IllegalArgumentException ex) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(name));
        }

        return this.runAsyncIo(() -> Files.isDirectory(directory))
            .thenCompose(existsOnDisk -> this.runOnGlobalThread(() -> {
                final World loadedWorld = Bukkit.getWorld(normalizedName);
                final boolean tracked = this.worldsFileStore.isTracked(normalizedName);
                if (!existsOnDisk && loadedWorld == null && !tracked) {
                    return OperationOutcome.failure(this.message("service.world_not_found", MessagePlaceholder.of("world", normalizedName)));
                }

                final Path resolvedDirectory = loadedWorld != null
                    ? loadedWorld.getWorldFolder().toPath().toAbsolutePath().normalize()
                    : directory;
                return OperationOutcome.success(
                    "World information is ready.",
                    this.describe(normalizedName, resolvedDirectory, loadedWorld, existsOnDisk)
                );
            }));
    }

    @Override
    public CompletableFuture<OperationOutcome<World>> createWorld(final String name, final World.Environment environment, final Long seed) {
        final String normalizedName = this.normalizeWorldName(name).orElse(null);
        if (normalizedName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(name));
        }

        final Path target;
        try {
            target = this.resolveWorldPath(normalizedName);
        } catch (final IllegalArgumentException ex) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(name));
        }

        return this.runAsyncIo(() -> Files.exists(target))
            .thenCompose(existsOnDisk -> this.runOnGlobalThread(() -> {
                if (Bukkit.getWorld(normalizedName) != null) {
                    return OperationOutcome.failure(this.message("service.world_already_loaded", MessagePlaceholder.of("world", normalizedName)));
                }
                if (existsOnDisk) {
                    return OperationOutcome.failure(this.message("service.world_folder_exists_use_load", MessagePlaceholder.of("world", normalizedName)));
                }

                final WorldDefaults defaults = this.configStore.settings().defaults();
                final WorldCreator creator = WorldCreator.name(normalizedName)
                    .environment(environment)
                    .generateStructures(defaults.generateStructures())
                    .hardcore(defaults.hardcore());

                if (seed != null) {
                    creator.seed(seed);
                }

                final World world = Bukkit.createWorld(creator);
                if (world == null) {
                    return OperationOutcome.failure(this.message("service.canvas_create_null"));
                }

                this.worldsFileStore.trackWorld(world.getName(), world.getEnvironment(), null);
                this.invalidateDirectorySnapshot();
                return OperationOutcome.success(this.message("service.world_created", MessagePlaceholder.of("world", world.getName())), world);
            }));
    }

    @Override
    public CompletableFuture<OperationOutcome<World>> loadWorld(final String name, final World.Environment environment) {
        final String normalizedName = this.normalizeWorldName(name).orElse(null);
        if (normalizedName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(name));
        }

        final Path target;
        try {
            target = this.resolveWorldPath(normalizedName);
        } catch (final IllegalArgumentException ex) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(name));
        }

        return this.runAsyncIo(() -> Files.isDirectory(target))
            .thenCompose(existsOnDisk -> this.runOnGlobalThread(() -> {
                final World existing = Bukkit.getWorld(normalizedName);
                if (existing != null) {
                    this.worldsFileStore.rememberEnvironment(existing.getName(), existing.getEnvironment());
                    return OperationOutcome.success(this.message("service.world_already_loaded", MessagePlaceholder.of("world", normalizedName)), existing);
                }
                if (!existsOnDisk) {
                    return OperationOutcome.failure(this.message("service.world_folder_missing_use_create", MessagePlaceholder.of("world", normalizedName)));
                }

                final WorldDefaults defaults = this.configStore.settings().defaults();
                final World.Environment resolvedEnvironment = this.resolveEnvironmentForLoad(normalizedName, environment);

                final World world = Bukkit.createWorld(
                    WorldCreator.name(normalizedName)
                        .environment(resolvedEnvironment)
                        .generateStructures(defaults.generateStructures())
                        .hardcore(defaults.hardcore())
                );

                if (world == null) {
                    return OperationOutcome.failure(this.message("service.canvas_load_null"));
                }

                this.worldsFileStore.trackWorld(world.getName(), world.getEnvironment(), null);
                return OperationOutcome.success(this.message("service.world_loaded", MessagePlaceholder.of("world", world.getName())), world);
            }));
    }

    @Override
    public CompletableFuture<OperationOutcome<Void>> unloadWorld(final String name, final boolean save) {
        final String normalizedName = this.normalizeWorldName(name).orElse(null);
        if (normalizedName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(name));
        }

        final CompletableFuture<OperationOutcome<Void>> future = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> {
            final World world = Bukkit.getWorld(normalizedName);
            if (world == null) {
                future.complete(OperationOutcome.failure(this.message("service.world_not_loaded", MessagePlaceholder.of("world", normalizedName))));
                return;
            }

            Bukkit.getServer().unloadWorldAsync(normalizedName, save, result -> future.complete(this.mapUnloadResult(normalizedName, result)));
        });
        return future;
    }

    @Override
    public CompletableFuture<OperationOutcome<Void>> deleteWorld(final String name, final boolean save) {
        final String normalizedName = this.normalizeWorldName(name).orElse(null);
        if (normalizedName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(name));
        }

        final Path target;
        try {
            target = this.resolveWorldPath(normalizedName);
        } catch (final IllegalArgumentException ex) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(name));
        }

        return this.runAsyncIo(() -> Files.isDirectory(target))
            .thenCompose(existsOnDisk -> this.ensureUnloaded(normalizedName, save).thenCompose(unloadOutcome -> {
                if (!unloadOutcome.success()) {
                    return CompletableFuture.completedFuture(unloadOutcome);
                }
                if (!existsOnDisk) {
                    return CompletableFuture.completedFuture(
                        OperationOutcome.<Void>failure(this.message("service.world_folder_missing", MessagePlaceholder.of("world", normalizedName)))
                    );
                }

                return this.runAsyncIo(() -> {
                    try {
                        this.deleteDirectory(target);
                    } catch (final IOException ex) {
                        throw new IllegalStateException("Failed to delete world folder '" + normalizedName + "'.", ex);
                    }
                    return OperationOutcome.<Void>success(this.message("service.world_deleted", MessagePlaceholder.of("world", normalizedName)), null);
                }).thenApply(outcome -> {
                    this.worldsFileStore.removeWorld(normalizedName);
                    this.invalidateDirectorySnapshot();
                    return outcome;
                }).exceptionally(throwable -> OperationOutcome.<Void>failure(this.normalizeThrowable(this.unwrap(throwable))));
            }));
    }

    @Override
    public CompletableFuture<OperationOutcome<Void>> untrackWorld(final String name) {
        final String normalizedName = this.normalizeWorldName(name).orElse(null);
        if (normalizedName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(name));
        }

        return this.runOnGlobalThread(() -> {
            if (!this.worldsFileStore.isTracked(normalizedName)) {
                return OperationOutcome.failure(this.message("service.world_not_tracked", MessagePlaceholder.of("world", normalizedName)));
            }

            this.worldsFileStore.removeWorld(normalizedName);
            return OperationOutcome.success(this.message("service.world_untracked", MessagePlaceholder.of("world", normalizedName)), null);
        });
    }

    @Override
    public CompletableFuture<OperationOutcome<Void>> importWorld(final String name, final World.Environment environment) {
        final String normalizedName = this.normalizeWorldName(name).orElse(null);
        if (normalizedName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(name));
        }

        final Path target;
        try {
            target = this.resolveWorldPath(normalizedName);
        } catch (final IllegalArgumentException ex) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(name));
        }

        return this.runAsyncIo(() -> Files.isDirectory(target))
            .thenCompose(existsOnDisk -> this.runOnGlobalThread(() -> {
                final World loadedWorld = Bukkit.getWorld(normalizedName);
                if (!existsOnDisk && loadedWorld == null) {
                    return OperationOutcome.failure(this.message("service.world_or_memory_missing", MessagePlaceholder.of("world", normalizedName)));
                }

                final World.Environment resolvedEnvironment = loadedWorld != null
                    ? loadedWorld.getEnvironment()
                    : this.resolveEnvironmentForLoad(normalizedName, environment);

                final Location spawn = loadedWorld != null && this.configStore.settings().captureSpawnOnImport()
                    ? loadedWorld.getSpawnLocation()
                    : null;

                this.worldsFileStore.trackWorld(normalizedName, resolvedEnvironment, spawn);
                return OperationOutcome.success(this.message("service.world_imported", MessagePlaceholder.of("world", normalizedName)), null);
            }));
    }

    @Override
    public CompletableFuture<OperationOutcome<Void>> copyWorld(final String sourceName, final String targetName, final boolean loadCopiedWorld) {
        final String normalizedSourceName = this.normalizeWorldName(sourceName).orElse(null);
        if (normalizedSourceName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(sourceName));
        }

        final String normalizedTargetName = this.normalizeWorldName(targetName).orElse(null);
        if (normalizedTargetName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(targetName));
        }

        final Path sourcePath;
        final Path targetPath;
        try {
            sourcePath = this.resolveWorldPath(normalizedSourceName);
            targetPath = this.resolveWorldPath(normalizedTargetName);
        } catch (final IllegalArgumentException ex) {
            return CompletableFuture.completedFuture(OperationOutcome.failure(this.normalizeThrowable(ex)));
        }

        final CompletableFuture<OperationOutcome<Void>> future = this.prepareCopy(normalizedSourceName, normalizedTargetName, sourcePath, targetPath)
            .thenCompose(preparationOutcome -> {
                if (!preparationOutcome.success() || preparationOutcome.value() == null) {
                    return CompletableFuture.completedFuture(OperationOutcome.<Void>failure(preparationOutcome.message()));
                }

                final CopyPreparation preparation = preparationOutcome.value();
                final CompletableFuture<OperationOutcome<Void>> unloadFuture = preparation.sourceWasLoaded()
                    ? this.unloadWorld(normalizedSourceName, true).thenApply(unloadOutcome -> unloadOutcome.success()
                        ? unloadOutcome
                        : OperationOutcome.<Void>failure(
                            this.message("service.copy_unload_failed", MessagePlaceholder.of("reason", this.messagesStore.plainText(unloadOutcome.message())))
                        ))
                    : CompletableFuture.completedFuture(OperationOutcome.success("Source world is already offline.", null));

                return unloadFuture.thenCompose(unloadOutcome -> {
                    if (!unloadOutcome.success()) {
                        return CompletableFuture.completedFuture(unloadOutcome);
                    }

                    return this.runAsyncIo(() -> {
                        try {
                            this.copyDirectory(sourcePath, targetPath);
                        } catch (final IOException ex) {
                            throw new IllegalStateException("Failed to copy world folder '" + normalizedSourceName + "'.", ex);
                        }
                        this.invalidateDirectorySnapshot();
                        this.worldsFileStore.copyWorldSettings(normalizedSourceName, normalizedTargetName, preparation.environment());
                        return OperationOutcome.<Void>success(this.message(
                            "service.copy_copied",
                            MessagePlaceholder.of("source", normalizedSourceName),
                            MessagePlaceholder.of("target", normalizedTargetName)
                        ), null);
                    }).thenCompose(copyOutcome -> this.finishCopy(preparation, normalizedSourceName, normalizedTargetName, loadCopiedWorld, copyOutcome));
                });
            });

        return future.handle((outcome, throwable) -> throwable != null
            ? OperationOutcome.<Void>failure(this.normalizeThrowable(this.unwrap(throwable)))
            : outcome
        );
    }

    @Override
    public CompletableFuture<OperationOutcome<Void>> teleportPlayer(final Player player, final String worldName) {
        return this.teleport(player, worldName, false);
    }

    @Override
    public CompletableFuture<OperationOutcome<Void>> sendPlayerToSpawn(final Player player, final String worldName) {
        return this.teleport(player, worldName, true);
    }

    @Override
    public CompletableFuture<OperationOutcome<Void>> setSpawn(final String worldName, final Location location) {
        final String normalizedName = this.normalizeWorldName(worldName).orElse(null);
        if (normalizedName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(worldName));
        }

        return this.runOnGlobalThread(() -> {
            final World world = Bukkit.getWorld(normalizedName);
            if (world == null) {
                return OperationOutcome.failure(this.message("service.world_not_loaded", MessagePlaceholder.of("world", normalizedName)));
            }

            final Location spawn = new Location(
                world,
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
            );
            this.worldsFileStore.rememberEnvironment(world.getName(), world.getEnvironment());
            this.worldsFileStore.rememberSpawn(world.getName(), spawn);
            return OperationOutcome.success(this.message("service.spawn_updated", MessagePlaceholder.of("world", world.getName())), null);
        });
    }

    @Override
    public CompletableFuture<OperationOutcome<Void>> setPortalTarget(final String worldName, final PortalKind portalKind, final String targetWorldName) {
        final String normalizedWorldName = this.normalizeWorldName(worldName).orElse(null);
        if (normalizedWorldName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(worldName));
        }
        if (!this.isClearValue(targetWorldName) && this.normalizeWorldName(targetWorldName).isEmpty()) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(targetWorldName));
        }

        return this.runOnGlobalThread(() -> {
            final World world = Bukkit.getWorld(normalizedWorldName);
            if (world == null) {
                return OperationOutcome.failure(this.message("service.world_not_loaded", MessagePlaceholder.of("world", normalizedWorldName)));
            }

            if (this.isClearValue(targetWorldName)) {
                this.worldsFileStore.clearPortalWorld(world.getName(), portalKind);
                return OperationOutcome.success(
                    this.message(
                        "service.portal_route_cleared",
                        MessagePlaceholder.of("portal", portalKind.displayName()),
                        MessagePlaceholder.of("world", world.getName())
                    ),
                    null
                );
            }

            final String normalizedTargetWorldName = this.normalizeWorldName(targetWorldName).orElseThrow();
            final World targetWorld = Bukkit.getWorld(normalizedTargetWorldName);
            if (targetWorld == null) {
                return OperationOutcome.failure(this.message("service.target_world_not_loaded", MessagePlaceholder.of("world", normalizedTargetWorldName)));
            }

            this.worldsFileStore.rememberEnvironment(world.getName(), world.getEnvironment());
            this.worldsFileStore.rememberPortalWorld(world.getName(), portalKind, targetWorld.getName());
            return OperationOutcome.success(
                this.message(
                    "service.portal_route_set",
                    MessagePlaceholder.of("portal", portalKind.displayName()),
                    MessagePlaceholder.of("world", world.getName()),
                    MessagePlaceholder.of("target", targetWorld.getName())
                ),
                null
            );
        });
    }

    @Override
    public CompletableFuture<OperationOutcome<Void>> setPortalTransfer(final String worldName, final PortalKind portalKind, final String endpoint) {
        final String normalizedWorldName = this.normalizeWorldName(worldName).orElse(null);
        if (normalizedWorldName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(worldName));
        }

        if (this.isClearValue(endpoint)) {
            return this.runOnGlobalThread(() -> {
                final World world = Bukkit.getWorld(normalizedWorldName);
                if (world == null) {
                    return OperationOutcome.failure(this.message("service.world_not_loaded", MessagePlaceholder.of("world", normalizedWorldName)));
                }

                this.worldsFileStore.clearPortalTransfer(world.getName(), portalKind);
                return OperationOutcome.success(
                    this.message(
                        "service.transfer_route_cleared",
                        MessagePlaceholder.of("portal", portalKind.displayName()),
                        MessagePlaceholder.of("world", world.getName())
                    ),
                    null
                );
            });
        }

        final ServerTransferTarget transferTarget;
        try {
            transferTarget = ServerTransferTarget.parse(endpoint);
        } catch (final TransferTargetParseException ex) {
            return CompletableFuture.completedFuture(OperationOutcome.failure(this.message(ex.messageKey())));
        }

        return this.runOnGlobalThread(() -> {
            final World world = Bukkit.getWorld(normalizedWorldName);
            if (world == null) {
                return OperationOutcome.failure(this.message("service.world_not_loaded", MessagePlaceholder.of("world", normalizedWorldName)));
            }

            this.worldsFileStore.rememberEnvironment(world.getName(), world.getEnvironment());
            this.worldsFileStore.rememberPortalTransfer(world.getName(), portalKind, transferTarget);
            return OperationOutcome.success(
                this.message(
                    "service.transfer_route_set",
                    MessagePlaceholder.of("portal", portalKind.displayName()),
                    MessagePlaceholder.of("world", world.getName()),
                    MessagePlaceholder.of("target", transferTarget.asConfigValue())
                ),
                null
            );
        });
    }

    @Override
    public CompletableFuture<OperationOutcome<Void>> reload() {
        return this.runAsyncIo(() -> {
            this.configStore.reload();
            this.worldsFileStore.reload();
            final MessagesReloadResult reloadResult = this.messagesStore.reload();
            if (!reloadResult.success()) {
                return OperationOutcome.<Void>failure(this.message(
                    "general.reload_invalid_lang",
                    MessagePlaceholder.of("reason", reloadResult.reason())
                ));
            }
            return OperationOutcome.<Void>success(this.message("general.reload_success"), null);
        }).exceptionally(throwable -> OperationOutcome.<Void>failure(this.normalizeThrowable(this.unwrap(throwable))));
    }

    @Override
    public CompletableFuture<List<String>> suggestKnownWorlds() {
        return this.runAsyncIo(this::readDiskWorldState)
            .thenCompose(diskState -> this.runOnGlobalThread(() -> {
                final Set<String> names = new LinkedHashSet<>();
                names.addAll(diskState.names());
                names.addAll(this.worldsFileStore.trackedWorldNames());
                for (final World world : Bukkit.getWorlds()) {
                    names.add(world.getName());
                }
                return OperationOutcome.success(
                    "Suggestions ready.",
                    names.stream()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList()
                );
            }))
            .thenApply(OperationOutcome::value);
    }

    @Override
    public CompletableFuture<List<String>> suggestLoadedWorlds() {
        return this.runOnGlobalThread(() -> {
            final List<String> names = Bukkit.getWorlds().stream()
                .map(World::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
            return OperationOutcome.success("Suggestions ready.", names);
        }).thenApply(OperationOutcome::value);
    }

    @Override
    public CompletableFuture<List<String>> suggestTrackedWorlds() {
        return this.runAsyncIo(this.worldsFileStore::trackedWorldNames);
    }

    @Override
    public CompletableFuture<List<String>> suggestDiskWorlds() {
        return this.runAsyncIo(() -> this.readDiskWorldState().directories().stream()
            .map(path -> path.getFileName().toString())
            .toList());
    }

    @Override
    public CompletableFuture<List<String>> suggestOnlinePlayers() {
        return this.runOnGlobalThread(() -> OperationOutcome.success(
            "Suggestions ready.",
            Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList()
        )).thenApply(OperationOutcome::value);
    }

    @Override
    public void reply(final CommandSender sender, final String message) {
        final net.kyori.adventure.text.Component component = this.messagesStore.deserialize(message);
        if (sender instanceof final Player player) {
            player.getScheduler().execute(this.plugin, () -> player.sendMessage(component), null, 1L);
            return;
        }

        Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> sender.sendMessage(component));
    }

    private CompletableFuture<Void> loadTrackedWorldOnStartup(final String worldName) {
        if (Bukkit.getWorld(worldName) != null) {
            final World world = Bukkit.getWorld(worldName);
            if (world != null) {
                this.worldsFileStore.rememberEnvironment(world.getName(), world.getEnvironment());
            }
            return CompletableFuture.completedFuture(null);
        }

        final Path target;
        try {
            target = this.resolveWorldPath(worldName);
        } catch (final IllegalArgumentException ex) {
            this.plugin.getLogger().warning("Tracked world '" + worldName + "' was skipped on startup because its path is invalid.");
            return CompletableFuture.completedFuture(null);
        }

        return this.runAsyncIo(() -> Files.isDirectory(target)).thenCompose(existsOnDisk -> {
            if (!existsOnDisk) {
                this.plugin.getLogger().warning("Tracked world '" + worldName + "' was skipped on startup because its folder is missing.");
                return CompletableFuture.completedFuture(null);
            }

            return this.loadWorld(worldName, null).handle((outcome, throwable) -> {
                this.logStartupLoadResult(worldName, outcome, throwable);
                return null;
            });
        });
    }

    private void logStartupLoadResult(final String worldName, final OperationOutcome<World> outcome, final Throwable throwable) {
        if (throwable != null) {
            final Throwable unwrapped = this.unwrap(throwable);
            this.plugin.getLogger().warning("Tracked world '" + worldName + "' failed to load on startup: " + unwrapped.getMessage());
            return;
        }
        if (outcome == null) {
            this.plugin.getLogger().warning("Tracked world '" + worldName + "' failed to load on startup for an unknown reason.");
            return;
        }
        if (!outcome.success()) {
            this.plugin.getLogger().warning("Tracked world '" + worldName + "' failed to load on startup: " + this.messagesStore.plainText(outcome.message()));
            return;
        }
        this.plugin.getLogger().info("Tracked world '" + worldName + "' loaded on startup.");
    }

    private CompletableFuture<OperationOutcome<Void>> teleport(final Player player, final String worldName, final boolean useConfiguredSpawn) {
        final String normalizedWorldName = this.normalizeWorldName(worldName).orElse(null);
        if (normalizedWorldName == null) {
            return CompletableFuture.completedFuture(this.invalidWorldNameOutcome(worldName));
        }

        final CompletableFuture<OperationOutcome<Void>> future = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> {
            final World target = Bukkit.getWorld(normalizedWorldName);
            if (target == null) {
                future.complete(OperationOutcome.failure(this.message("service.world_not_loaded", MessagePlaceholder.of("world", normalizedWorldName))));
                return;
            }

            final Location location = useConfiguredSpawn
                ? this.worldsFileStore.resolveSpawn(target)
                : target.getSpawnLocation();

            player.teleportAsync(location).whenComplete((success, throwable) -> {
                if (throwable != null) {
                    future.complete(OperationOutcome.failure(
                        this.message("service.teleport_failed", MessagePlaceholder.of("reason", throwable.getMessage()))
                    ));
                } else if (Boolean.TRUE.equals(success)) {
                    future.complete(OperationOutcome.success(
                        useConfiguredSpawn
                            ? this.message("service.teleport_spawn", MessagePlaceholder.of("world", target.getName()))
                            : this.message("service.teleport_world", MessagePlaceholder.of("world", target.getName())),
                        null
                    ));
                } else {
                    future.complete(OperationOutcome.failure(this.message("service.teleport_rejected")));
                }
            });
        });
        return future;
    }

    private CompletableFuture<OperationOutcome<Void>> ensureUnloaded(final String name, final boolean save) {
        return this.runOnGlobalThread(() -> OperationOutcome.success("Loaded state checked.", Bukkit.getWorld(name) != null))
            .thenCompose(loadedOutcome -> Boolean.TRUE.equals(loadedOutcome.value())
                ? this.unloadWorld(name, save)
                : CompletableFuture.completedFuture(OperationOutcome.<Void>success("World is already unloaded.", null))
            );
    }

    private OperationOutcome<Void> mapUnloadResult(final String name, final WorldUnloadResult result) {
        return switch (result) {
            case SUCCESS -> OperationOutcome.success(this.message("service.unload.success", MessagePlaceholder.of("world", name)), null);
            case FAIL_PLAYERS_JOINING -> OperationOutcome.failure(this.message("service.unload.players_joining"));
            case FAIL_PLAYERS_PRESENT -> OperationOutcome.failure(this.message("service.unload.players_present"));
            case FAIL_ALREADY_UNLOADING -> OperationOutcome.failure(this.message("service.unload.already_unloading"));
            case FAIL_IS_OVERWORLD -> OperationOutcome.failure(this.message("service.unload.overworld"));
            case FAIL_UNLOAD_EVENT -> OperationOutcome.failure(this.message("service.unload.cancelled"));
            case FAIL_IS_SHUTDOWN -> OperationOutcome.failure(this.message("service.unload.shutdown"));
            case FAIL_UNKNOWN -> OperationOutcome.failure(this.message("service.unload.unknown"));
        };
    }

    private WorldDescriptor describe(final String name, final Path directory, final World world, final boolean existsOnDisk) {
        if (world == null) {
            return new WorldDescriptor(
                name,
                false,
                existsOnDisk,
                this.worldsFileStore.isTracked(name),
                directory,
                this.worldsFileStore.environment(name),
                null,
                null,
                null,
                this.worldsFileStore.spawnSummary(name)
            );
        }

        this.worldsFileStore.rememberEnvironment(world.getName(), world.getEnvironment());
        return new WorldDescriptor(
            world.getName(),
            true,
            existsOnDisk,
            this.worldsFileStore.isTracked(world.getName()),
            directory,
            world.getEnvironment(),
            world.getPlayers().size(),
            world.isHardcore(),
            world.canGenerateStructures(),
            this.worldsFileStore.spawnSummary(world.getName())
        );
    }

    private World.Environment resolveEnvironmentForLoad(final String name, final World.Environment requestedEnvironment) {
        if (requestedEnvironment != null) {
            return requestedEnvironment;
        }

        final World.Environment storedEnvironment = this.worldsFileStore.environment(name);
        if (storedEnvironment != null) {
            return storedEnvironment;
        }

        final String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("_nether")) {
            return World.Environment.NETHER;
        }
        if (normalized.endsWith("_the_end") || normalized.endsWith("_end")) {
            return World.Environment.THE_END;
        }
        return this.configStore.settings().defaults().environment();
    }

    private boolean looksLikeWorldFolder(final Path directory) {
        return Files.exists(directory.resolve("level.dat"));
    }

    private Path resolveWorldPath(final String worldName) {
        final String normalizedName = this.normalizeWorldName(worldName)
            .orElseThrow(() -> new IllegalArgumentException("Invalid managed world name: " + worldName));
        final Path resolved = this.worldContainer.resolve(normalizedName).toAbsolutePath().normalize();
        if (!resolved.startsWith(this.worldContainer)) {
            throw new IllegalArgumentException("Refusing to use a path outside the world container: " + normalizedName);
        }
        return resolved;
    }

    private Path safeResolveWorldPath(final String worldName) {
        try {
            return this.resolveWorldPath(worldName);
        } catch (final IllegalArgumentException ex) {
            return this.worldContainer.resolve(String.valueOf(worldName)).toAbsolutePath().normalize();
        }
    }

    private Optional<String> normalizeWorldName(final String raw) {
        return WorldNameRules.normalize(raw);
    }

    private boolean isClearValue(final String raw) {
        if (raw == null) {
            return false;
        }

        final String normalized = raw.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("off") || normalized.equals("clear") || normalized.equals("none");
    }

    private void copyDirectory(final Path source, final Path target) throws IOException {
        final Path normalizedSource = source.toAbsolutePath().normalize();
        final Path normalizedTarget = target.toAbsolutePath().normalize();

        if (!normalizedSource.startsWith(this.worldContainer)) {
            throw new IOException("Refusing to copy from a path outside the world container: " + normalizedSource);
        }
        if (!normalizedTarget.startsWith(this.worldContainer)) {
            throw new IOException("Refusing to copy to a path outside the world container: " + normalizedTarget);
        }

        try (Stream<Path> stream = Files.walk(normalizedSource)) {
            stream.forEach(sourcePath -> {
                final Path relative = normalizedSource.relativize(sourcePath);
                final Path targetPath = normalizedTarget.resolve(relative);
                try {
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                        return;
                    }

                    final String fileName = sourcePath.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (fileName.equals("uid.dat") || fileName.equals("session.lock")) {
                        return;
                    }

                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourcePath, targetPath);
                } catch (final IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (final RuntimeException ex) {
            if (ex.getCause() instanceof final IOException ioException) {
                throw ioException;
            }
            throw ex;
        }
    }

    private void deleteDirectory(final Path target) throws IOException {
        final Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(this.worldContainer)) {
            throw new IOException("Refusing to delete a path outside the world container: " + normalizedTarget);
        }

        try (Stream<Path> stream = Files.walk(normalizedTarget)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (final RuntimeException ex) {
            if (ex.getCause() instanceof final IOException ioException) {
                throw ioException;
            }
            throw ex;
        }
    }

    private <T> CompletableFuture<OperationOutcome<T>> runOnGlobalThread(final Supplier<OperationOutcome<T>> supplier) {
        final CompletableFuture<OperationOutcome<T>> future = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> {
            try {
                future.complete(supplier.get());
            } catch (final Throwable throwable) {
                future.complete(OperationOutcome.failure(this.normalizeThrowable(throwable)));
            }
        });
        return future;
    }

    private <T> CompletableFuture<T> runAsyncIo(final Supplier<T> supplier) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            try {
                future.complete(supplier.get());
            } catch (final Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private Throwable unwrap(final Throwable throwable) {
        return throwable.getCause() != null ? throwable.getCause() : throwable;
    }

    private String normalizeThrowable(final Throwable throwable) {
        final String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return this.message("general.internal_error", MessagePlaceholder.of("reason", throwable.getClass().getSimpleName()));
        }

        return this.message("general.internal_error", MessagePlaceholder.of("reason", message.strip()));
    }

    private <T> OperationOutcome<T> invalidWorldNameOutcome(final String input) {
        return OperationOutcome.failure(this.message("general.invalid_world_name", MessagePlaceholder.of("input", String.valueOf(input))));
    }

    private CompletableFuture<OperationOutcome<CopyPreparation>> prepareCopy(
        final String sourceName,
        final String targetName,
        final Path sourcePath,
        final Path targetPath
    ) {
        if (sourceName.equalsIgnoreCase(targetName)) {
            return CompletableFuture.completedFuture(OperationOutcome.failure(this.message("service.copy_same_world")));
        }

        return this.runAsyncIo(() -> new CopyDiskState(Files.isDirectory(sourcePath), Files.exists(targetPath)))
            .thenCompose(diskState -> {
                if (!diskState.sourceExists()) {
                    return CompletableFuture.completedFuture(
                        OperationOutcome.<CopyPreparation>failure(
                            this.message("service.copy_source_missing", MessagePlaceholder.of("world", sourceName))
                        )
                    );
                }
                if (diskState.targetExists()) {
                    return CompletableFuture.completedFuture(
                        OperationOutcome.<CopyPreparation>failure(
                            this.message("service.copy_target_exists", MessagePlaceholder.of("world", targetName))
                        )
                    );
                }

                return this.runOnGlobalThread(() -> {
                    if (Bukkit.getWorld(targetName) != null) {
                        return OperationOutcome.<CopyPreparation>failure(
                            this.message("service.target_world_loaded", MessagePlaceholder.of("world", targetName))
                        );
                    }

                    final World sourceWorld = Bukkit.getWorld(sourceName);
                    if (sourceWorld != null && !sourceWorld.getPlayers().isEmpty()) {
                        return OperationOutcome.<CopyPreparation>failure(
                            this.message("service.copy_source_not_empty")
                        );
                    }

                    final World.Environment environment = sourceWorld != null
                        ? sourceWorld.getEnvironment()
                        : this.resolveEnvironmentForLoad(sourceName, null);
                    return OperationOutcome.success(
                        "Copy preparation is ready.",
                        new CopyPreparation(sourceWorld != null, environment)
                    );
                });
            });
    }

    private CompletableFuture<OperationOutcome<Void>> finishCopy(
        final CopyPreparation preparation,
        final String sourceName,
        final String targetName,
        final boolean loadCopiedWorld,
        final OperationOutcome<Void> copyOutcome
    ) {
        return this.restoreSourceWorldIfNeeded(preparation, sourceName).thenCompose(restoreOutcome -> {
            if (!copyOutcome.success()) {
                return CompletableFuture.completedFuture(this.combineCopyFailure(copyOutcome, restoreOutcome));
            }
            if (!restoreOutcome.success()) {
                return CompletableFuture.completedFuture(OperationOutcome.failure(
                    this.message(
                        "service.copy_restore_failed",
                        MessagePlaceholder.of("target", targetName),
                        MessagePlaceholder.of("reason", this.messagesStore.plainText(restoreOutcome.message()))
                    )
                ));
            }
            if (!loadCopiedWorld) {
                return CompletableFuture.completedFuture(copyOutcome);
            }
            if (preparation.environment() == null) {
                return CompletableFuture.completedFuture(OperationOutcome.failure(
                    this.message("service.copy_missing_environment_for_load")
                ));
            }

            return this.loadWorld(targetName, preparation.environment()).thenApply(loadOutcome -> {
                if (!loadOutcome.success()) {
                    return OperationOutcome.<Void>failure(
                        this.message("service.copy_load_failed", MessagePlaceholder.of("reason", this.messagesStore.plainText(loadOutcome.message())))
                    );
                }
                return OperationOutcome.<Void>success(this.message("service.copy_copied_loaded", MessagePlaceholder.of("target", targetName)), null);
            });
        });
    }

    private CompletableFuture<OperationOutcome<Void>> restoreSourceWorldIfNeeded(final CopyPreparation preparation, final String sourceName) {
        if (!preparation.sourceWasLoaded()) {
            return CompletableFuture.completedFuture(OperationOutcome.success("Source world state did not change.", null));
        }
        if (preparation.environment() == null) {
            return CompletableFuture.completedFuture(OperationOutcome.failure(
                this.message("service.copy_restore_missing_environment")
            ));
        }

        return this.loadWorld(sourceName, preparation.environment()).thenApply(loadOutcome -> loadOutcome.success()
            ? OperationOutcome.<Void>success("Source world was restored after copying.", null)
            : OperationOutcome.<Void>failure(loadOutcome.message())
        );
    }

    private OperationOutcome<Void> combineCopyFailure(
        final OperationOutcome<Void> copyOutcome,
        final OperationOutcome<Void> restoreOutcome
    ) {
        if (restoreOutcome.success()) {
            return copyOutcome;
        }
        return OperationOutcome.failure(this.message(
            "service.copy_combined_failure",
            MessagePlaceholder.of("reason", this.messagesStore.plainText(copyOutcome.message())),
            MessagePlaceholder.of("restore_reason", this.messagesStore.plainText(restoreOutcome.message()))
        ));
    }

    private String message(final String key, final MessagePlaceholder... placeholders) {
        return this.messagesStore.message(key, placeholders);
    }

    private DiskWorldState readDiskWorldState() {
        final long now = System.currentTimeMillis();
        final DirectorySnapshot cachedSnapshot = this.directorySnapshot;
        if (cachedSnapshot.isFresh(now)) {
            return cachedSnapshot.asState();
        }
        if (!Files.isDirectory(this.worldContainer)) {
            final DirectorySnapshot emptySnapshot = DirectorySnapshot.empty(now);
            this.directorySnapshot = emptySnapshot;
            return emptySnapshot.asState();
        }

        try (Stream<Path> stream = Files.list(this.worldContainer)) {
            final List<Path> directories = stream
                .filter(Files::isDirectory)
                .filter(this::looksLikeWorldFolder)
                .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
            final List<String> names = directories.stream()
                .map(path -> path.getFileName().toString())
                .toList();
            final DirectorySnapshot refreshedSnapshot = new DirectorySnapshot(now, List.copyOf(directories), Set.copyOf(names));
            this.directorySnapshot = refreshedSnapshot;
            return refreshedSnapshot.asState();
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to read world folders.", ex);
        }
    }

    private void invalidateDirectorySnapshot() {
        this.directorySnapshot = DirectorySnapshot.empty();
    }

    private record DiskWorldState(List<Path> directories, Set<String> names) {
    }

    private record CopyDiskState(boolean sourceExists, boolean targetExists) {
    }

    private record CopyPreparation(boolean sourceWasLoaded, World.Environment environment) {
    }

    private record DirectorySnapshot(long loadedAtMillis, List<Path> directories, Set<String> names) {

        private static DirectorySnapshot empty() {
            return empty(0L);
        }

        private static DirectorySnapshot empty(final long loadedAtMillis) {
            return new DirectorySnapshot(loadedAtMillis, List.of(), Set.of());
        }

        private boolean isFresh(final long now) {
            return now - this.loadedAtMillis <= WORLD_DIRECTORY_CACHE_TTL_MILLIS;
        }

        private DiskWorldState asState() {
            return new DiskWorldState(this.directories, this.names);
        }
    }
}