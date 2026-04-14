package io.yunuservices.world;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {

    private PluginConfigStore configStore;
    private WorldsFileStore worldsFileStore;
    private MessagesStore messagesStore;

    @Override
    public void onEnable() {
        if (!this.isCanvasRuntime()) {
            this.getLogger().severe("This plugin is Canvas-only. A compatible Canvas runtime was not detected.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.messagesStore = new MessagesStore(this);
        this.configStore = new PluginConfigStore(this);
        this.worldsFileStore = new WorldsFileStore(this);

        final CanvasWorldManagerService service = new CanvasWorldManagerService(this, this.configStore, this.worldsFileStore, this.messagesStore);
        Bukkit.getPluginManager().registerEvents(new WorldPortalListener(this, this.worldsFileStore, this.messagesStore), this);
        new WorldCommands(this, service).register();
        service.loadTrackedWorldsOnStartup();
        this.getLogger().info("World has been enabled. Canvas world manager is ready.");
    }

    public PluginConfigStore configStore() {
        return this.configStore;
    }

    public WorldsFileStore worldsFileStore() {
        return this.worldsFileStore;
    }

    public MessagesStore messagesStore() {
        return this.messagesStore;
    }

    private boolean isCanvasRuntime() {
        return true;
    }
}
