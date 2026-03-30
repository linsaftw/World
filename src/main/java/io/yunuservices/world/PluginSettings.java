package io.yunuservices.world;

public record PluginSettings(
    WorldDefaults defaults,
    boolean unloadSaveByDefault,
    boolean deleteSaveByDefault,
    boolean loadCopiedWorldByDefault,
    boolean captureSpawnOnImport
) {
}
