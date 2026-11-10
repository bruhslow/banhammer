package me.giridharan.banhammer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Manages the dedicated dependencies folder:
 * plugins/BanHammer/dependencies/
 *   ├── dependencies.yml     (Hard & soft dependencies, on-death, on-give triggers)
 *   └── linked-plugins.yml   (Plugin linking: LiteBans, AdvancedBan, Essentials, DiscordSRV, etc.)
 */
public class DependencyManager {

    private final BanHammerPlugin plugin;
    private File dependenciesFolder;
    private File dependencyFile;
    private File linkedPluginsFile;
    private FileConfiguration dependencyConfig;
    private FileConfiguration linkedPluginsConfig;

    public DependencyManager(BanHammerPlugin plugin) {
        this.plugin = plugin;
        setupFiles();
        load();
    }

    private void setupFiles() {
        // Primary directory: plugins/BanHammer/dependencies/
        // Also supports plugins/BanHammer/config/dependencies/ if configured
        File altDir = new File(new File(plugin.getDataFolder(), "config"), "dependencies");
        if (altDir.exists() && altDir.isDirectory()) {
            this.dependenciesFolder = altDir;
        } else {
            this.dependenciesFolder = new File(plugin.getDataFolder(), "dependencies");
        }

        if (!this.dependenciesFolder.exists()) {
            this.dependenciesFolder.mkdirs();
        }

        this.dependencyFile = new File(this.dependenciesFolder, "dependencies.yml");
        this.linkedPluginsFile = new File(this.dependenciesFolder, "linked-plugins.yml");

        // Backwards compatibility: If an old dependencies.yml existed in plugins/BanHammer/, move it to the new folder
        File oldRootDep = new File(plugin.getDataFolder(), "dependencies.yml");
        if (oldRootDep.exists() && !this.dependencyFile.exists()) {
            try {
                if (oldRootDep.renameTo(this.dependencyFile)) {
                    plugin.getLogger().info("Migrated dependencies.yml into the dedicated dependencies/ folder.");
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void load() {
        // 1. Ensure dependencies.yml exists
        if (!dependencyFile.exists()) {
            if (plugin.getResource("dependencies/dependencies.yml") != null) {
                plugin.saveResource("dependencies/dependencies.yml", false);
            } else if (plugin.getResource("dependencies.yml") != null) {
                plugin.saveResource("dependencies.yml", false);
                File created = new File(plugin.getDataFolder(), "dependencies.yml");
                if (created.exists() && !dependencyFile.exists()) {
                    created.renameTo(dependencyFile);
                }
            }
        }
        dependencyConfig = YamlConfiguration.loadConfiguration(dependencyFile);
        InputStream depStream = plugin.getResource("dependencies/dependencies.yml");
        if (depStream == null) {
            depStream = plugin.getResource("dependencies.yml");
        }
        if (depStream != null) {
            YamlConfiguration defDep = YamlConfiguration.loadConfiguration(new InputStreamReader(depStream, StandardCharsets.UTF_8));
            dependencyConfig.setDefaults(defDep);
        }

        // 2. Ensure linked-plugins.yml exists
        if (!linkedPluginsFile.exists()) {
            if (plugin.getResource("dependencies/linked-plugins.yml") != null) {
                plugin.saveResource("dependencies/linked-plugins.yml", false);
            }
        }
        if (linkedPluginsFile.exists()) {
            linkedPluginsConfig = YamlConfiguration.loadConfiguration(linkedPluginsFile);
            InputStream linkedStream = plugin.getResource("dependencies/linked-plugins.yml");
            if (linkedStream != null) {
                YamlConfiguration defLinked = YamlConfiguration.loadConfiguration(new InputStreamReader(linkedStream, StandardCharsets.UTF_8));
                linkedPluginsConfig.setDefaults(defLinked);
            }
        } else {
            linkedPluginsConfig = new YamlConfiguration();
        }
    }

    public void checkDependencies() {
        // Check Hard Dependencies from dependencies.yml
        List<String> hardDeps = dependencyConfig.getStringList("dependencies");
        for (String dep : hardDeps) {
            Plugin p = Bukkit.getPluginManager().getPlugin(dep);
            if (p == null || !p.isEnabled()) {
                plugin.getLogger().severe("❌ [Dependency Error] Missing required dependency: '" + dep + "'!");
            } else {
                plugin.getLogger().info("✔ [Dependency] Found required dependency: " + dep + " v" + p.getDescription().getVersion());
            }
        }

        // Check Soft Dependencies from dependencies.yml
        List<String> softDeps = dependencyConfig.getStringList("soft-dependencies");
        for (String dep : softDeps) {
            Plugin p = Bukkit.getPluginManager().getPlugin(dep);
            if (p != null && p.isEnabled()) {
                plugin.getLogger().info("🔗 [Dependency] Successfully hooked into soft dependency: " + dep + " v" + p.getDescription().getVersion());
            }
        }

        // Check Linked Plugins from linked-plugins.yml
        ConfigurationSection linkedSec = linkedPluginsConfig.getConfigurationSection("linked-plugins");
        if (linkedSec != null) {
            for (String key : linkedSec.getKeys(false)) {
                ConfigurationSection sec = linkedSec.getConfigurationSection(key);
                if (sec == null) continue;

                boolean enabled = sec.getBoolean("enabled", false);
                String pluginName = sec.getString("plugin-name", key);
                String type = sec.getString("type", "SOFT").toUpperCase();

                Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
                if (p != null && p.isEnabled()) {
                    if (enabled) {
                        plugin.getLogger().info("✔ [Linked Plugin] Active & linked: " + pluginName + " v" + p.getDescription().getVersion());
                    }
                } else {
                    if (enabled) {
                        if ("HARD".equals(type)) {
                            plugin.getLogger().severe("❌ [Dependency Error] Required linked plugin '" + pluginName + "' is missing or disabled!");
                        } else {
                            plugin.getLogger().warning("⚠ [Linked Plugin] Enabled plugin '" + pluginName + "' is not installed on this server.");
                        }
                    }
                }
            }
        }
    }

    public boolean isExternalBansEnabled() {
        if (dependencyConfig.getBoolean("external-ban-commands.enabled", false)) {
            return true;
        }
        ConfigurationSection linkedSec = linkedPluginsConfig.getConfigurationSection("linked-plugins");
        if (linkedSec != null) {
            for (String key : linkedSec.getKeys(false)) {
                ConfigurationSection sec = linkedSec.getConfigurationSection(key);
                if (sec != null && sec.getBoolean("enabled", false)) {
                    List<String> cmds = sec.getStringList("on-ban-commands");
                    if (!cmds.isEmpty()) return true;
                }
            }
        }
        return false;
    }

    public boolean keepNativeBan() {
        boolean keepDep = dependencyConfig.getBoolean("external-ban-commands.keep-native-ban", true);
        boolean keepLinked = linkedPluginsConfig.getBoolean("keep-native-ban", true);
        return keepDep && keepLinked;
    }

    /**
     * Dispatches external ban commands from both dependencies.yml and linked-plugins.yml
     */
    public void executeExternalBans(String victim, UUID victimUuid, String killer, UUID killerUuid, String reason, Location loc) {
        // 1. Dispatch from dependencies.yml
        if (dependencyConfig.getBoolean("external-ban-commands.enabled", false)) {
            dispatchList(
                    dependencyConfig.getStringList("external-ban-commands.commands"),
                    dependencyConfig.getBoolean("external-ban-commands.as-console", true),
                    victim, victimUuid, killer, killerUuid, reason, loc
            );
        }

        // 2. Dispatch from linked-plugins.yml
        ConfigurationSection linkedSec = linkedPluginsConfig.getConfigurationSection("linked-plugins");
        if (linkedSec != null) {
            for (String key : linkedSec.getKeys(false)) {
                ConfigurationSection sec = linkedSec.getConfigurationSection(key);
                if (sec == null || !sec.getBoolean("enabled", false)) continue;

                String pluginName = sec.getString("plugin-name", key);
                Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
                if (p != null && !p.isEnabled()) continue;

                List<String> cmds = sec.getStringList("on-ban-commands");
                boolean asConsole = sec.getBoolean("as-console", true);
                dispatchList(cmds, asConsole, victim, victimUuid, killer, killerUuid, reason, loc);
            }
        }
    }

    public void executeExternalBans(String victim, String killer, String reason) {
        executeExternalBans(victim, null, killer, null, reason, null);
    }

    /**
     * Dispatches fatal blow commands from both dependencies.yml and linked-plugins.yml
     */
    public void executeDeathCommands(String victim, UUID victimUuid, String killer, UUID killerUuid, String reason, Location loc) {
        // 1. Dispatch from dependencies.yml
        if (dependencyConfig.getBoolean("on-death-commands.enabled", false)) {
            dispatchList(
                    dependencyConfig.getStringList("on-death-commands.commands"),
                    dependencyConfig.getBoolean("on-death-commands.as-console", true),
                    victim, victimUuid, killer, killerUuid, reason, loc
            );
        }

        // 2. Dispatch from linked-plugins.yml
        ConfigurationSection linkedSec = linkedPluginsConfig.getConfigurationSection("linked-plugins");
        if (linkedSec != null) {
            for (String key : linkedSec.getKeys(false)) {
                ConfigurationSection sec = linkedSec.getConfigurationSection(key);
                if (sec == null || !sec.getBoolean("enabled", false)) continue;

                String pluginName = sec.getString("plugin-name", key);
                Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
                if (p != null && !p.isEnabled()) continue;

                List<String> cmds = sec.getStringList("on-death-commands");
                boolean asConsole = sec.getBoolean("as-console", true);
                dispatchList(cmds, asConsole, victim, victimUuid, killer, killerUuid, reason, loc);
            }
        }
    }

    /**
     * Dispatches hammer-give commands from dependencies.yml
     */
    public void executeHammerGiveCommands(Player recipient, CommandSender sender) {
        if (!dependencyConfig.getBoolean("on-hammer-give-commands.enabled", false)) return;

        String senderName = sender != null ? sender.getName() : "Console";
        UUID recipientUuid = recipient != null ? recipient.getUniqueId() : null;
        String recipientName = recipient != null ? recipient.getName() : "";
        Location loc = recipient != null ? recipient.getLocation() : null;

        dispatchList(
                dependencyConfig.getStringList("on-hammer-give-commands.commands"),
                dependencyConfig.getBoolean("on-hammer-give-commands.as-console", true),
                recipientName, recipientUuid, senderName, null, "", loc
        );
    }

    private void dispatchList(List<String> commands, boolean asConsole, String victim, UUID victimUuid, String killer, UUID killerUuid, String reason, Location loc) {
        if (commands == null || commands.isEmpty()) return;

        CommandSender sender = asConsole ? Bukkit.getConsoleSender() : null;
        if (!asConsole && killer != null) {
            sender = Bukkit.getPlayer(killer);
        }
        if (sender == null) {
            sender = Bukkit.getConsoleSender();
        }

        final CommandSender finalSender = sender;
        for (String cmd : commands) {
            if (cmd == null || cmd.trim().isEmpty()) continue;
            String formatted = formatPlaceholders(cmd, victim, victimUuid, killer, killerUuid, reason, loc);
            try {
                Bukkit.dispatchCommand(finalSender, formatted);
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to execute command [" + formatted + "]: " + t.getMessage());
            }
        }
    }

    private String formatPlaceholders(String cmd, String victim, UUID victimUuid, String killer, UUID killerUuid, String reason, Location loc) {
        String result = cmd;
        if (victim != null) {
            result = result.replace("%victim%", victim).replace("%player%", victim);
        }
        if (victimUuid != null) {
            result = result.replace("%victim_uuid%", victimUuid.toString());
        }
        if (killer != null) {
            result = result.replace("%killer%", killer).replace("%admin%", killer);
        }
        if (killerUuid != null) {
            result = result.replace("%killer_uuid%", killerUuid.toString());
        }
        if (reason != null) {
            result = result.replace("%reason%", reason);
        }
        if (loc != null) {
            if (loc.getWorld() != null) {
                result = result.replace("%world%", loc.getWorld().getName());
            }
            result = result
                    .replace("%x%", String.valueOf(loc.getBlockX()))
                    .replace("%y%", String.valueOf(loc.getBlockY()))
                    .replace("%z%", String.valueOf(loc.getBlockZ()));
        }
        return result;
    }

    public File getDependenciesFolder() {
        return dependenciesFolder;
    }

    public FileConfiguration getDependencyConfig() {
        return dependencyConfig;
    }

    public FileConfiguration getLinkedPluginsConfig() {
        return linkedPluginsConfig;
    }
}
