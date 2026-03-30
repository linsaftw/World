package io.yunuservices.world;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.parser.standard.BooleanParser;
import org.incendo.cloud.parser.standard.DoubleParser;
import org.incendo.cloud.parser.standard.FloatParser;
import org.incendo.cloud.parser.standard.LongParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;

public final class WorldCommands {

    private static final List<String> ENVIRONMENTS = List.of("NORMAL", "NETHER", "THE_END");
    private static final int MAX_SUGGESTIONS = 64;
    private static final List<String> CLEAR_VALUES = List.of("off", "clear", "none");

    private final Main plugin;
    private final MessagesStore messagesStore;
    private final WorldManagerService service;
    private final CommandManager<CommandSourceStack> commandManager;

    public WorldCommands(final Main plugin, final WorldManagerService service) {
        this.plugin = plugin;
        this.messagesStore = plugin.messagesStore();
        this.service = service;
        this.commandManager = PaperCommandManager.builder()
            .executionCoordinator(
                ExecutionCoordinator.<CommandSourceStack>builder()
                    .executor(ExecutionCoordinator.nonSchedulingExecutor())
                    .build()
            )
            .buildOnEnable(plugin);
    }

    public void register() {
        this.commandManager.command(this.base("list", "world.command.list").handler(this::handleList));
        this.commandManager.command(this.base("info", "world.command.info")
            .required("name", StringParser.stringParser(), this.knownWorldSuggestions())
            .handler(this::handleInfo));
        this.commandManager.command(this.base("create", "world.command.create")
            .required("name", StringParser.stringParser())
            .optional("environment", StringParser.stringParser(), this.environmentSuggestions())
            .optional("seed", LongParser.longParser())
            .handler(this::handleCreate));
        this.commandManager.command(this.base("load", "world.command.load")
            .required("name", StringParser.stringParser(), this.diskWorldSuggestions())
            .optional("environment", StringParser.stringParser(), this.environmentSuggestions())
            .handler(this::handleLoad));
        this.commandManager.command(this.base("unload", "world.command.unload")
            .required("name", StringParser.stringParser(), this.loadedWorldSuggestions())
            .optional("save", BooleanParser.booleanParser(), SuggestionProvider.suggestingStrings("true", "false"))
            .handler(this::handleUnload));
        this.commandManager.command(this.base("delete", "world.command.delete")
            .required("name", StringParser.stringParser(), this.knownWorldSuggestions())
            .optional("save", BooleanParser.booleanParser(), SuggestionProvider.suggestingStrings("true", "false"))
            .handler(this::handleDelete));
        this.commandManager.command(this.base("import", "world.command.import")
            .required("name", StringParser.stringParser(), this.diskWorldSuggestions())
            .optional("environment", StringParser.stringParser(), this.environmentSuggestions())
            .handler(this::handleImport));
        this.commandManager.command(this.base("copy", "world.command.copy")
            .required("source", StringParser.stringParser(), this.knownWorldSuggestions())
            .required("target", StringParser.stringParser())
            .optional("load", BooleanParser.booleanParser(), SuggestionProvider.suggestingStrings("true", "false"))
            .handler(this::handleCopy));
        this.commandManager.command(this.base("tp", "world.command.tp")
            .required("world", StringParser.stringParser(), this.loadedWorldSuggestions())
            .optional("player", StringParser.stringParser(), this.playerSuggestions())
            .handler(this::handleTeleport));
        this.commandManager.command(this.base("spawn", "world.command.spawn").handler(this::handleSpawnSelf));
        this.commandManager.command(this.base("spawn", "world.command.spawn")
            .required("world", StringParser.stringParser(), this.loadedWorldSuggestions())
            .handler(this::handleSpawnWorld));
        this.commandManager.command(this.base("spawn", "world.command.spawn")
            .required("world", StringParser.stringParser(), this.loadedWorldSuggestions())
            .required("player", StringParser.stringParser(), this.playerSuggestions())
            .handler(this::handleSpawnOther));
        this.commandManager.command(this.base("setspawn", "world.command.setspawn").handler(this::handleSetSpawnCurrent));
        this.commandManager.command(this.base("setspawn", "world.command.setspawn")
            .required("world", StringParser.stringParser(), this.loadedWorldSuggestions())
            .handler(this::handleSetSpawnWorldCurrentSpawn));
        this.commandManager.command(this.base("setspawn", "world.command.setspawn")
            .required("world", StringParser.stringParser(), this.loadedWorldSuggestions())
            .required("x", DoubleParser.doubleParser())
            .required("y", DoubleParser.doubleParser())
            .required("z", DoubleParser.doubleParser())
            .optional("yaw", FloatParser.floatParser())
            .optional("pitch", FloatParser.floatParser())
            .handler(this::handleSetSpawnExplicit));
        this.commandManager.command(this.setBase("portal", "world.command.set.portal")
            .required("world", StringParser.stringParser(), this.loadedWorldSuggestions())
            .required("portal", StringParser.stringParser(), this.portalSuggestions())
            .required("target", StringParser.stringParser(), this.loadedWorldOrClearSuggestions())
            .handler(this::handleSetPortal));
        this.commandManager.command(this.setBase("transfer", "world.command.set.transfer")
            .required("world", StringParser.stringParser(), this.loadedWorldSuggestions())
            .required("portal", StringParser.stringParser(), this.portalSuggestions())
            .required("target", StringParser.stringParser(), this.clearOnlySuggestions())
            .handler(this::handleSetTransfer));
        this.commandManager.command(this.base("reload", "world.command.reload").handler(this::handleReload));
    }

    private Command.Builder<CommandSourceStack> base(final String literal, final String permission) {
        return this.commandManager.commandBuilder("world")
            .literal(literal)
            .permission(permission);
    }

    private Command.Builder<CommandSourceStack> setBase(final String literal, final String permission) {
        return this.commandManager.commandBuilder("world")
            .literal("set")
            .literal(literal)
            .permission(permission);
    }

    private void handleList(final CommandContext<CommandSourceStack> context) {
        this.handleAsync(this.sender(context), this.service.listWorlds(), outcome -> {
            if (!outcome.success()) {
                return List.of(outcome.message());
            }

            final List<WorldDescriptor> worlds = outcome.value();
            if (worlds == null || worlds.isEmpty()) {
                return List.of(this.messagesStore.message("general.no_worlds"));
            }

            return worlds.stream()
                .map(descriptor -> this.messagesStore.message(
                    "general.list_entry",
                    this.placeholder("name", descriptor.name()),
                    this.placeholder("state", descriptor.loaded() ? this.messagesStore.value("values.state.loaded") : this.messagesStore.value("values.state.disk")),
                    this.placeholder("tracked", descriptor.tracked()),
                    this.placeholder("environment", this.nullSafe(descriptor.environment()))
                ))
                .toList();
        });
    }

    private void handleInfo(final CommandContext<CommandSourceStack> context) {
        final String name = context.get("name");
        this.handleAsync(this.sender(context), this.service.describeWorld(name), outcome -> {
            if (!outcome.success() || outcome.value() == null) {
                return List.of(outcome.message());
            }

            final WorldDescriptor descriptor = outcome.value();
            return List.of(
                this.infoLine("values.info.name", descriptor.name()),
                this.infoLine("values.info.loaded", descriptor.loaded()),
                this.infoLine("values.info.exists_on_disk", descriptor.existsOnDisk()),
                this.infoLine("values.info.tracked", descriptor.tracked()),
                this.infoLine("values.info.path", this.normalizePath(descriptor.path())),
                this.infoLine("values.info.environment", this.nullSafe(descriptor.environment())),
                this.infoLine("values.info.players", this.nullSafe(descriptor.playerCount())),
                this.infoLine("values.info.hardcore", this.nullSafe(descriptor.hardcore())),
                this.infoLine("values.info.generate_structures", this.nullSafe(descriptor.generatesStructures())),
                this.infoLine("values.info.configured_spawn", this.nullSafe(descriptor.configuredSpawn())),
                this.infoLine("values.info.nether_portal", this.plugin.worldsFileStore().portalWorldSummary(name, PortalKind.NETHER)),
                this.infoLine("values.info.nether_transfer", this.plugin.worldsFileStore().portalTransferSummary(name, PortalKind.NETHER)),
                this.infoLine("values.info.end_portal", this.plugin.worldsFileStore().portalWorldSummary(name, PortalKind.END)),
                this.infoLine("values.info.end_transfer", this.plugin.worldsFileStore().portalTransferSummary(name, PortalKind.END))
            );
        });
    }

    private void handleCreate(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = this.sender(context);
        final String name = context.get("name");
        final World.Environment environment = this.parseEnvironment(sender, context.getOrDefault("environment", null), true);
        if (environment == null) {
            return;
        }
        this.handleAsync(sender, this.service.createWorld(name, environment, context.getOrDefault("seed", null)), outcome -> List.of(outcome.message()));
    }

    private void handleLoad(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = this.sender(context);
        final String name = context.get("name");
        final World.Environment environment = this.parseEnvironment(sender, context.getOrDefault("environment", null), false);
        if (context.getOrDefault("environment", null) != null && environment == null) {
            return;
        }
        this.handleAsync(sender, this.service.loadWorld(name, environment), outcome -> List.of(outcome.message()));
    }

    private void handleUnload(final CommandContext<CommandSourceStack> context) {
        final boolean save = context.getOrDefault("save", this.plugin.configStore().settings().unloadSaveByDefault());
        this.handleAsync(this.sender(context), this.service.unloadWorld(context.get("name"), save), outcome -> List.of(outcome.message()));
    }

    private void handleDelete(final CommandContext<CommandSourceStack> context) {
        final boolean save = context.getOrDefault("save", this.plugin.configStore().settings().deleteSaveByDefault());
        this.handleAsync(this.sender(context), this.service.deleteWorld(context.get("name"), save), outcome -> List.of(outcome.message()));
    }

    private void handleImport(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = this.sender(context);
        final World.Environment environment = this.parseEnvironment(sender, context.getOrDefault("environment", null), false);
        if (context.getOrDefault("environment", null) != null && environment == null) {
            return;
        }
        this.handleAsync(sender, this.service.importWorld(context.get("name"), environment), outcome -> List.of(outcome.message()));
    }

    private void handleCopy(final CommandContext<CommandSourceStack> context) {
        final boolean loadCopiedWorld = context.getOrDefault("load", this.plugin.configStore().settings().loadCopiedWorldByDefault());
        this.handleAsync(
            this.sender(context),
            this.service.copyWorld(context.get("source"), context.get("target"), loadCopiedWorld),
            outcome -> List.of(outcome.message())
        );
    }

    private void handleTeleport(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = this.sender(context);
        final Player target = this.resolveTargetPlayer(sender, context.getOrDefault("player", null), "world.command.tp.other");
        if (target == null) {
            return;
        }

        this.handleAsync(sender, this.service.teleportPlayer(target, context.get("world")), outcome -> List.of(outcome.message()));
    }

    private void handleSpawnSelf(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = this.sender(context);
        if (!(sender instanceof final Player player)) {
            this.service.reply(sender, this.messagesStore.message("general.console_world_required"));
            return;
        }

        this.handleAsync(sender, this.service.sendPlayerToSpawn(player, player.getWorld().getName()), outcome -> List.of(outcome.message()));
    }

    private void handleSpawnWorld(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = this.sender(context);
        final Player target = this.resolveTargetPlayer(sender, null, "world.command.spawn.other");
        if (target == null) {
            return;
        }

        this.handleAsync(sender, this.service.sendPlayerToSpawn(target, context.get("world")), outcome -> List.of(outcome.message()));
    }

    private void handleSpawnOther(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = this.sender(context);
        final Player target = this.resolveTargetPlayer(sender, context.get("player"), "world.command.spawn.other");
        if (target == null) {
            return;
        }

        this.handleAsync(sender, this.service.sendPlayerToSpawn(target, context.get("world")), outcome -> List.of(outcome.message()));
    }

    private void handleSetSpawnCurrent(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = this.sender(context);
        if (!(sender instanceof final Player player)) {
            this.service.reply(sender, this.messagesStore.message("general.console_world_coordinates_required"));
            return;
        }

        this.handleAsync(sender, this.service.setSpawn(player.getWorld().getName(), player.getLocation()), outcome -> List.of(outcome.message()));
    }

    private void handleSetSpawnWorldCurrentSpawn(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = this.sender(context);
        final String worldName = context.get("world");
        final World world = Bukkit.getWorld(worldName);
        if (world == null) {
            this.service.reply(sender, this.messagesStore.message("service.world_not_loaded", this.placeholder("world", worldName)));
            return;
        }

        this.handleAsync(sender, this.service.setSpawn(worldName, world.getSpawnLocation()), outcome -> List.of(outcome.message()));
    }

    private void handleSetSpawnExplicit(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = this.sender(context);
        final String worldName = context.get("world");
        final World world = Bukkit.getWorld(worldName);
        if (world == null) {
            this.service.reply(sender, this.messagesStore.message("service.world_not_loaded", this.placeholder("world", worldName)));
            return;
        }

        final float yaw = context.getOrDefault("yaw", 0.0F);
        final float pitch = context.getOrDefault("pitch", 0.0F);
        final Location location = new Location(world, context.get("x"), context.get("y"), context.get("z"), yaw, pitch);
        this.handleAsync(sender, this.service.setSpawn(worldName, location), outcome -> List.of(outcome.message()));
    }

    private void handleReload(final CommandContext<CommandSourceStack> context) {
        this.handleAsync(this.sender(context), this.service.reload(), outcome -> List.of(outcome.message()));
    }

    private void handleSetPortal(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = this.sender(context);
        final PortalKind portalKind = this.parsePortalKind(sender, context.get("portal"));
        if (portalKind == null) {
            return;
        }

        this.handleAsync(
            sender,
            this.service.setPortalTarget(context.get("world"), portalKind, context.get("target")),
            outcome -> List.of(outcome.message())
        );
    }

    private void handleSetTransfer(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = this.sender(context);
        final PortalKind portalKind = this.parsePortalKind(sender, context.get("portal"));
        if (portalKind == null) {
            return;
        }

        this.handleAsync(
            sender,
            this.service.setPortalTransfer(context.get("world"), portalKind, context.get("target")),
            outcome -> List.of(outcome.message())
        );
    }

    private Player resolveTargetPlayer(final CommandSender sender, final String playerName, final String otherPermission) {
        if (playerName == null) {
            if (sender instanceof final Player player) {
                return player;
            }
            this.service.reply(sender, this.messagesStore.message("general.console_player_required"));
            return null;
        }

        final Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) {
            this.service.reply(sender, this.messagesStore.message("general.player_not_found", this.placeholder("player", playerName)));
            return null;
        }
        if (!(sender instanceof Player) || !player.getUniqueId().equals(((Player) sender).getUniqueId())) {
            if (!sender.hasPermission(otherPermission)) {
                this.service.reply(sender, this.messagesStore.message("general.permission_required_other", this.placeholder("permission", otherPermission)));
                return null;
            }
        }
        return player;
    }

    private <T> void handleAsync(
        final CommandSender sender,
        final CompletableFuture<OperationOutcome<T>> future,
        final java.util.function.Function<OperationOutcome<T>, List<String>> formatter
    ) {
        future.whenComplete((outcome, throwable) -> {
            if (throwable != null) {
                this.service.reply(sender, this.messagesStore.message("general.operation_failed", this.placeholder("reason", throwable.getMessage())));
                return;
            }

            for (final String line : formatter.apply(outcome)) {
                this.service.reply(sender, line);
            }
        });
    }

    private SuggestionProvider<CommandSourceStack> environmentSuggestions() {
        return SuggestionProvider.suggestingStrings(ENVIRONMENTS);
    }

    private SuggestionProvider<CommandSourceStack> portalSuggestions() {
        return SuggestionProvider.suggestingStrings(PortalKind.suggestions());
    }

    private SuggestionProvider<CommandSourceStack> knownWorldSuggestions() {
        return (context, input) -> this.service.suggestKnownWorlds().thenApply(values -> this.toSuggestions(values, input));
    }

    private SuggestionProvider<CommandSourceStack> loadedWorldSuggestions() {
        return (context, input) -> this.service.suggestLoadedWorlds().thenApply(values -> this.toSuggestions(values, input));
    }

    private SuggestionProvider<CommandSourceStack> diskWorldSuggestions() {
        return (context, input) -> this.service.suggestDiskWorlds().thenApply(values -> this.toSuggestions(values, input));
    }

    private SuggestionProvider<CommandSourceStack> playerSuggestions() {
        return (context, input) -> this.service.suggestOnlinePlayers().thenApply(values -> this.toSuggestions(values, input));
    }

    private SuggestionProvider<CommandSourceStack> loadedWorldOrClearSuggestions() {
        return (context, input) -> this.service.suggestLoadedWorlds().thenApply(values -> {
            final java.util.ArrayList<String> options = new java.util.ArrayList<>(values);
            options.addAll(CLEAR_VALUES);
            return this.toSuggestions(options, input);
        });
    }

    private SuggestionProvider<CommandSourceStack> clearOnlySuggestions() {
        return (context, input) -> CompletableFuture.completedFuture(this.toSuggestions(CLEAR_VALUES, input));
    }

    private CommandSender sender(final CommandContext<CommandSourceStack> context) {
        return context.sender().getSender();
    }

    private World.Environment parseEnvironment(final CommandSender sender, final String raw, final boolean useDefaultWhenMissing) {
        if (raw == null || raw.isBlank()) {
            return useDefaultWhenMissing ? this.plugin.configStore().settings().defaults().environment() : null;
        }

        try {
            return World.Environment.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            this.service.reply(sender, this.messagesStore.message("general.invalid_environment", this.placeholder("input", raw)));
            return null;
        }
    }

    private PortalKind parsePortalKind(final CommandSender sender, final String raw) {
        final PortalKind portalKind = PortalKind.fromInput(raw);
        if (portalKind != null) {
            return portalKind;
        }

        this.service.reply(sender, this.messagesStore.message("general.invalid_portal", this.placeholder("input", raw)));
        return null;
    }

    private String normalizePath(final Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private String nullSafe(final Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private Iterable<Suggestion> toSuggestions(final List<String> values) {
        return values.stream()
            .map(Suggestion::suggestion)
            .toList();
    }

    private Iterable<Suggestion> toSuggestions(final List<String> values, final CommandInput input) {
        return this.toSuggestions(this.filterSuggestions(values, input.lastRemainingToken()));
    }

    private List<String> filterSuggestions(final List<String> values, final String token) {
        final String normalizedToken = token == null ? "" : token.strip();
        return values.stream()
            .filter(value -> normalizedToken.isEmpty() || value.regionMatches(true, 0, normalizedToken, 0, normalizedToken.length()))
            .limit(MAX_SUGGESTIONS)
            .toList();
    }

    private String infoLine(final String labelKey, final Object value) {
        return this.messagesStore.message(
            "general.info_line",
            this.placeholder("label", this.messagesStore.value(labelKey)),
            this.placeholder("value", value)
        );
    }

    private MessagePlaceholder placeholder(final String name, final Object value) {
        return MessagePlaceholder.of(name, value);
    }
}
