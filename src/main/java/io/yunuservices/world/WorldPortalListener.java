package io.yunuservices.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.plugin.Plugin;

public final class WorldPortalListener implements Listener {

    private final Plugin plugin;
    private final WorldsFileStore worldsFileStore;
    private final MessagesStore messagesStore;

    public WorldPortalListener(final Plugin plugin, final WorldsFileStore worldsFileStore, final MessagesStore messagesStore) {
        this.plugin = plugin;
        this.worldsFileStore = worldsFileStore;
        this.messagesStore = messagesStore;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(final PlayerPortalEvent event) {
        final World sourceWorld = event.getFrom().getWorld();
        if (sourceWorld == null) {
            return;
        }

        final PortalKind portalKind = PortalKind.fromCause(event.getCause());
        if (portalKind == null) {
            return;
        }

        final ServerTransferTarget transferTarget = this.worldsFileStore.portalTransfer(sourceWorld.getName(), portalKind);
        if (transferTarget != null) {
            event.setCancelled(true);
            this.transfer(event.getPlayer(), transferTarget);
            return;
        }

        final String targetWorldName = this.worldsFileStore.portalWorld(sourceWorld.getName(), portalKind);
        if (targetWorldName == null || targetWorldName.isBlank()) {
            return;
        }

        final World targetWorld = Bukkit.getWorld(targetWorldName);
        if (targetWorld == null) {
            this.plugin.getLogger().warning("Configured " + portalKind.configKey()
                + " portal target world '" + targetWorldName + "' is not loaded.");
            return;
        }

        final Location destination = event.getTo() == null
            ? this.worldsFileStore.resolveSpawn(targetWorld).clone()
            : event.getTo().clone();
        destination.setWorld(targetWorld);
        event.setTo(destination);
    }

    private void transfer(final Player player, final ServerTransferTarget transferTarget) {
        player.getScheduler().execute(this.plugin, () -> {
            try {
                player.transfer(transferTarget.host(), transferTarget.port());
            } catch (final NoSuchMethodError ex) {
                player.sendMessage(this.messagesStore.component("general.transfer_unavailable"));
            }
        }, null, 1L);
    }
}
