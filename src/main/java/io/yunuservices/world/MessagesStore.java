package io.yunuservices.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.plugin.Plugin;
import org.tomlj.Toml;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;

public final class MessagesStore {

    private static final String DEFAULT_MESSAGES = """
[general]
prefix = "<gradient:#1DC9FF:#36D7E8:#58E3C1:#8BE08D:#FFD166:#FF9F43>[World]</gradient>"
no_worlds = "<prefix> <yellow>No worlds were found.</yellow>"
list_entry = "<prefix> <gray><name> | state=<state> | tracked=<tracked> | environment=<environment></gray>"
info_line = "<prefix> <gray><label>=</gray><white><value></white>"
operation_failed = "<prefix> <red>Operation failed: <reason></red>"
invalid_environment = "<prefix> <red>Invalid environment: <input>. Valid values: NORMAL, NETHER, THE_END</red>"
invalid_portal = "<prefix> <red>Invalid portal type: <input>. Valid values: NETHER, END</red>"
console_world_required = "<prefix> <red>You must specify a world when running this command from console.</red>"
console_player_required = "<prefix> <red>You must specify a player when running this command from console.</red>"
console_world_coordinates_required = "<prefix> <red>You must specify a world and coordinates when running this command from console.</red>"
player_not_found = "<prefix> <red>Player not found: <player></red>"
permission_required_other = "<prefix> <red>You need the permission <permission> to target other players.</red>"
reload_success = "<prefix> <green>config.yml, worlds.yml and lang/messages.toml were reloaded.</green>"
reload_invalid_lang = "<prefix> <yellow>config.yml and worlds.yml were reloaded, but lang/messages.toml is invalid. Keeping the previously loaded messages. <reason></yellow>"
transfer_unavailable = "<prefix> <red>Server transfer is not available on this runtime.</red>"
internal_error = "<prefix> <red>An error occurred during the operation: <reason></red>"

[values.state]
loaded = "loaded"
disk = "disk"

[values.info]
name = "name"
loaded = "loaded"
exists_on_disk = "existsOnDisk"
tracked = "tracked"
path = "path"
environment = "environment"
players = "players"
hardcore = "hardcore"
generate_structures = "generateStructures"
configured_spawn = "configuredSpawn"
nether_portal = "netherPortal"
nether_transfer = "netherTransfer"
end_portal = "endPortal"
end_transfer = "endTransfer"

[service]
world_not_found = "<prefix> <red>World '<world>' was not found.</red>"
world_not_loaded = "<prefix> <red>World '<world>' is not loaded.</red>"
target_world_not_loaded = "<prefix> <red>Target world '<world>' is not loaded.</red>"
target_world_loaded = "<prefix> <red>Target world '<world>' is already loaded.</red>"
world_already_loaded = "<prefix> <yellow>World '<world>' is already loaded.</yellow>"
world_folder_exists_use_load = "<prefix> <red>The world folder already exists. Use /world load <world> instead.</red>"
world_folder_missing_use_create = "<prefix> <red>The world folder was not found. Use /world create <world> to create it.</red>"
world_folder_missing = "<prefix> <red>The world folder for '<world>' was not found.</red>"
world_or_memory_missing = "<prefix> <red>World '<world>' was not found on disk or in memory.</red>"
environment_missing_load = "<prefix> <red>The environment could not be determined. Use /world load <world> <NORMAL|NETHER|THE_END>.</red>"
environment_missing_import = "<prefix> <red>The environment could not be determined. Use /world import <world> <NORMAL|NETHER|THE_END>.</red>"
canvas_create_null = "<prefix> <red>Canvas returned null while creating the world.</red>"
canvas_load_null = "<prefix> <red>Canvas returned null while loading the world.</red>"
world_created = "<prefix> <green>World '<world>' was created and loaded.</green>"
world_loaded = "<prefix> <green>World '<world>' was loaded.</green>"
world_deleted = "<prefix> <green>World '<world>' was deleted.</green>"
world_imported = "<prefix> <green>World '<world>' was imported into worlds.yml.</green>"
spawn_updated = "<prefix> <green>Spawn for world '<world>' was updated.</green>"
portal_route_set = "<prefix> <green>Portal route for <portal> in world '<world>' now points to '<target>'.</green>"
portal_route_cleared = "<prefix> <green>Portal route for <portal> in world '<world>' was cleared.</green>"
transfer_route_set = "<prefix> <green>Transfer route for <portal> in world '<world>' now points to '<target>'.</green>"
transfer_route_cleared = "<prefix> <green>Transfer route for <portal> in world '<world>' was cleared.</green>"
transfer_target_invalid_format = "<prefix> <red>Transfer target must be in the format host[:port].</red>"
transfer_target_empty_port = "<prefix> <red>Transfer port cannot be empty.</red>"
transfer_target_port_range = "<prefix> <red>Transfer port must be between 1 and 65535.</red>"
transfer_target_port_number = "<prefix> <red>Transfer port must be a number.</red>"
transfer_target_empty_host = "<prefix> <red>Transfer host cannot be empty.</red>"
teleport_world = "<prefix> <green>The player was teleported to world '<world>'.</green>"
teleport_spawn = "<prefix> <green>The player was teleported to the configured spawn of world '<world>'.</green>"
teleport_failed = "<prefix> <red>Teleport failed: <reason></red>"
teleport_rejected = "<prefix> <red>Teleport was rejected.</red>"
copy_same_world = "<prefix> <red>Source and target worlds must be different.</red>"
copy_source_missing = "<prefix> <red>Source world folder '<world>' was not found.</red>"
copy_target_exists = "<prefix> <red>Target world folder '<world>' already exists.</red>"
copy_source_not_empty = "<prefix> <red>Safe copy requires the source world to be empty before it can be unloaded.</red>"
copy_unload_failed = "<prefix> <red>Safe copy could not unload the source world: <reason></red>"
copy_copied = "<prefix> <green>World '<source>' was copied to '<target>'.</green>"
copy_copied_loaded = "<prefix> <green>World '<target>' was copied and loaded.</green>"
copy_load_failed = "<prefix> <red>The world was copied, but loading failed: <reason></red>"
copy_restore_failed = "<prefix> <red>World '<target>' was copied, but the source world failed to reload: <reason></red>"
copy_restore_missing_environment = "<prefix> <red>The source world was unloaded for copying, but its environment could not be determined for reloading.</red>"
copy_missing_environment_for_load = "<prefix> <red>The world was copied, but its environment could not be determined for automatic loading.</red>"
copy_combined_failure = "<prefix> <red><reason> Source world restore also failed: <restore_reason></red>"

[service.unload]
success = "<prefix> <green>World '<world>' was unloaded successfully.</green>"
players_joining = "<prefix> <red>Unload failed: players are currently joining that world.</red>"
players_present = "<prefix> <red>Unload failed: players are currently present in that world.</red>"
already_unloading = "<prefix> <red>Unload failed: that world is already unloading.</red>"
overworld = "<prefix> <red>Unload failed: the overworld cannot be unloaded.</red>"
cancelled = "<prefix> <red>Unload failed: WorldUnloadEvent was cancelled.</red>"
shutdown = "<prefix> <red>Unload failed: the server is shutting down.</red>"
unknown = "<prefix> <red>Unload failed: unknown error.</red>"
""";

    private static final Map<String, String> DEFAULT_TEMPLATES = parseDefaults();

    private final Plugin plugin;
    private final MiniMessage miniMessage;
    private final Path directory;
    private final Path file;
    private volatile Map<String, String> templates;

    public MessagesStore(final Plugin plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.directory = plugin.getDataFolder().toPath().resolve("lang");
        this.file = this.directory.resolve("messages.toml");
        this.templates = DEFAULT_TEMPLATES;
        this.initialize();
    }

    public synchronized MessagesReloadResult reload() {
        this.ensureDirectory();
        return this.loadFromDisk();
    }

    public String message(final String key, final MessagePlaceholder... placeholders) {
        return this.miniMessage.serialize(this.component(key, placeholders));
    }

    public Component component(final String key, final MessagePlaceholder... placeholders) {
        final TagResolver.Builder resolver = TagResolver.builder();
        resolver.resolver(Placeholder.component("prefix", this.prefixComponent()));
        for (final MessagePlaceholder placeholder : placeholders) {
            resolver.resolver(Placeholder.unparsed(placeholder.name(), placeholder.value()));
        }
        return this.miniMessage.deserialize(this.value(key), resolver.build());
    }

    public Component deserialize(final String input) {
        return this.miniMessage.deserialize(input);
    }

    public String plainText(final String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return input.replaceAll("<[^>]+>", "").strip();
    }

    public String value(final String key) {
        final String current = this.templates.get(key);
        if (current != null) {
            return current;
        }
        return DEFAULT_TEMPLATES.getOrDefault(key, key);
    }

    private void initialize() {
        this.ensureDirectory();
        if (!Files.exists(this.file)) {
            this.writeDefaults();
        }

        final MessagesReloadResult result = this.loadFromDisk();
        if (!result.success()) {
            this.plugin.getLogger().warning("lang/messages.toml is invalid. Using the embedded defaults. " + result.reason());
        }
    }

    private MessagesReloadResult loadFromDisk() {
        final String input;
        try {
            input = Files.readString(this.file, StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            return MessagesReloadResult.invalid("Failed to read lang/messages.toml: " + ex.getMessage());
        }

        final TomlParseResult parseResult = Toml.parse(input);
        if (parseResult.hasErrors()) {
            return MessagesReloadResult.invalid(this.formatErrors(parseResult.errors()));
        }

        final Map<String, String> loadedTemplates = new LinkedHashMap<>(DEFAULT_TEMPLATES);
        for (final String key : parseResult.dottedKeySet()) {
            if (!parseResult.isString(key)) {
                return MessagesReloadResult.invalid("Only string values are allowed. Invalid key: " + key);
            }
            loadedTemplates.put(key, parseResult.getString(key));
        }

        this.templates = Map.copyOf(loadedTemplates);
        return MessagesReloadResult.loaded();
    }

    private Component prefixComponent() {
        return this.miniMessage.deserialize(this.templates.getOrDefault("general.prefix", DEFAULT_TEMPLATES.get("general.prefix")));
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(this.directory);
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to create lang directory.", ex);
        }
    }

    private void writeDefaults() {
        try {
            Files.writeString(
                this.file,
                DEFAULT_MESSAGES,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to create lang/messages.toml.", ex);
        }
    }

    private String formatErrors(final List<TomlParseError> errors) {
        return errors.stream()
            .map(TomlParseError::toString)
            .collect(java.util.stream.Collectors.joining(" | "));
    }

    private static Map<String, String> parseDefaults() {
        final TomlParseResult parseResult = Toml.parse(DEFAULT_MESSAGES);
        if (parseResult.hasErrors()) {
            throw new ExceptionInInitializerError(parseResult.errors().toString());
        }

        final Map<String, String> templates = new LinkedHashMap<>();
        for (final String key : parseResult.dottedKeySet()) {
            if (parseResult.isString(key)) {
                templates.put(key, parseResult.getString(key));
            }
        }
        return Map.copyOf(templates);
    }
}
