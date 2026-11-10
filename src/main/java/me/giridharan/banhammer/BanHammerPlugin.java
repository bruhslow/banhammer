package me.giridharan.banhammer;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class BanHammerPlugin extends JavaPlugin {

    private static BanHammerPlugin instance;
    private HammerManager hammerManager;
    private ReasonManager reasonManager;
    private DependencyManager dependencyManager;
    private BanHammerListener listener;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        this.hammerManager = new HammerManager(this);
        this.reasonManager = new ReasonManager(this);
        this.dependencyManager = new DependencyManager(this);
        this.dependencyManager.checkDependencies();

        // Register event listener
        this.listener = new BanHammerListener(this);
        getServer().getPluginManager().registerEvents(this.listener, this);

        // Register command and tab completion
        BanHammerCommand command = new BanHammerCommand(this);
        registerCommand("banhammer", command);

        getLogger().info("BanHammer v" + getDescription().getVersion() + " has been enabled!");
    }

    private void registerCommand(String name, BanHammerCommand command) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }
    }

    @Override
    public void onDisable() {
        if (reasonManager != null) {
            reasonManager.saveData();
        }
        getLogger().info("BanHammer has been disabled.");
    }

    public static BanHammerPlugin getInstance() {
        return instance;
    }

    public HammerManager getHammerManager() {
        return hammerManager;
    }

    public ReasonManager getReasonManager() {
        return reasonManager;
    }

    public DependencyManager getDependencyManager() {
        return dependencyManager;
    }

    public BanHammerListener getListener() {
        return listener;
    }
}
