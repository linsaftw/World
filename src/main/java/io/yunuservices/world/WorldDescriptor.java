package io.yunuservices.world;

import java.nio.file.Path;

import org.bukkit.World;

public record WorldDescriptor(
    String name,
    boolean loaded,
    boolean existsOnDisk,
    boolean tracked,
    Path path,
    World.Environment environment,
    Integer playerCount,
    Boolean hardcore,
    Boolean generatesStructures,
    String configuredSpawn
) {
}
