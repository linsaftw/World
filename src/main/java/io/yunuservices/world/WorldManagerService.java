package io.yunuservices.world;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;

import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public interface WorldManagerService {

    CompletableFuture<OperationOutcome<List<WorldDescriptor>>> listWorlds();

    CompletableFuture<OperationOutcome<WorldDescriptor>> describeWorld(String name);

    CompletableFuture<OperationOutcome<World>> createWorld(String name, World.Environment environment, Long seed);

    CompletableFuture<OperationOutcome<World>> loadWorld(String name, World.Environment environment);

    CompletableFuture<OperationOutcome<Void>> unloadWorld(String name, boolean save);

    CompletableFuture<OperationOutcome<Void>> deleteWorld(String name, boolean save);

    CompletableFuture<OperationOutcome<Void>> importWorld(String name, World.Environment environment);

    CompletableFuture<OperationOutcome<Void>> copyWorld(String sourceName, String targetName, boolean loadCopiedWorld);

    CompletableFuture<OperationOutcome<Void>> teleportPlayer(Player player, String worldName);

    CompletableFuture<OperationOutcome<Void>> sendPlayerToSpawn(Player player, String worldName);

    CompletableFuture<OperationOutcome<Void>> setSpawn(String worldName, Location location);

    CompletableFuture<OperationOutcome<Void>> setPortalTarget(String worldName, PortalKind portalKind, String targetWorldName);

    CompletableFuture<OperationOutcome<Void>> setPortalTransfer(String worldName, PortalKind portalKind, String endpoint);

    CompletableFuture<OperationOutcome<Void>> reload();

    CompletableFuture<List<String>> suggestKnownWorlds();

    CompletableFuture<List<String>> suggestLoadedWorlds();

    CompletableFuture<List<String>> suggestDiskWorlds();

    CompletableFuture<List<String>> suggestOnlinePlayers();

    void reply(CommandSender sender, String message);
}
