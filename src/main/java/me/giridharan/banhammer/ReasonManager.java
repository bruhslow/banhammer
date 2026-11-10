package me.giridharan.banhammer;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance ReasonManager:
 * - O(1) in-memory caching for all pending and active ban reasons.
 * - Asynchronous background disk persistence to eliminate main-thread lag.
 * - Thread-safe ConcurrentHashMaps for zero-lock reads on login and combat.
 */
public class ReasonManager {

    private final BanHammerPlugin plugin;
    private final File dataFile;
    private YamlConfiguration dataConfig;

    private static class BanRecord {
        final String reason;
        final String source;

        BanRecord(String reason, String source) {
            this.reason = reason;
            this.source = source;
        }
    }

    // In-memory caches for O(1) instantaneous access
    private final Map<String, String> pendingReasons = new ConcurrentHashMap<>();
    private final Map<String, BanRecord> bannedCache = new ConcurrentHashMap<>();
    private final AtomicBoolean isSaving = new AtomicBoolean(false);

    public ReasonManager(BanHammerPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "reasons.yml");
        loadData();
    }

    public synchronized void loadData() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Could not create reasons.yml: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        pendingReasons.clear();
        bannedCache.clear();

        // Load pending reasons into memory
        if (dataConfig.isConfigurationSection("pending")) {
            ConfigurationSection sec = dataConfig.getConfigurationSection("pending");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    String val = sec.getString(key);
                    if (val != null) {
                        pendingReasons.put(key.toLowerCase(), val);
                    }
                }
            }
        }

        // Load active ban records into memory
        if (dataConfig.isConfigurationSection("banned")) {
            ConfigurationSection sec = dataConfig.getConfigurationSection("banned");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    String reason = sec.getString(key + ".reason");
                    String source = sec.getString(key + ".source");
                    if (reason != null) {
                        bannedCache.put(key.toLowerCase(), new BanRecord(reason, source != null ? source : "The Ban Hammer"));
                    }
                }
            }
        }
    }

    /**
     * Synchronous save (used on plugin disable to ensure data flush).
     */
    public synchronized void saveData() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save reasons.yml: " + e.getMessage());
        }
    }

    /**
     * Asynchronous save to offload disk I/O from the main server thread.
     */
    public void saveDataAsync() {
        if (isSaving.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    synchronized (this) {
                        dataConfig.save(dataFile);
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to save reasons.yml asynchronously: " + e.getMessage());
                } finally {
                    isSaving.set(false);
                }
            });
        }
    }

    /**
     * Sets a pending reason for a player before they are killed by the Ban Hammer.
     */
    public void setPendingReason(String playerName, String reason) {
        String lower = playerName.toLowerCase();
        pendingReasons.put(lower, reason);
        synchronized (this) {
            dataConfig.set("pending." + lower, reason);
        }
        saveDataAsync();
    }

    /**
     * Gets pending reason without consuming it.
     */
    public String getPendingReason(String playerName) {
        return pendingReasons.get(playerName.toLowerCase());
    }

    /**
     * Retrieves the pending reason when struck by Ban Hammer and clears it from pending.
     */
    public String consumePendingReason(String playerName) {
        String lower = playerName.toLowerCase();
        String reason = pendingReasons.remove(lower);
        synchronized (this) {
            dataConfig.set("pending." + lower, null);
        }
        saveDataAsync();

        if (reason == null || reason.trim().isEmpty()) {
            reason = plugin.getConfig().getString("default-reason", "Struck down by the Ban Hammer");
        }
        return reason;
    }

    /**
     * Updates the ban reason in memory, in Bukkit's BanList, and persists asynchronously.
     */
    public void updateBanReason(String playerName, String newReason, String source) {
        if (newReason == null || newReason.trim().isEmpty()) {
            newReason = plugin.getConfig().getString("default-reason", "Struck down by the Ban Hammer");
        }
        if (source == null || source.trim().isEmpty()) {
            source = "The Ban Hammer";
        }

        String lower = playerName.toLowerCase();

        // 1. Update in-memory cache for O(1) instant lookups
        bannedCache.put(lower, new BanRecord(newReason, source));

        // 2. Update native Bukkit BanList
        try {
            Bukkit.getBanList(BanList.Type.NAME).addBan(
                    playerName,
                    newReason,
                    (Date) null,
                    source
            );
        } catch (Throwable ignored) {}

        // 3. Update configuration structure and save asynchronously
        synchronized (this) {
            dataConfig.set("banned." + lower + ".reason", newReason);
            dataConfig.set("banned." + lower + ".source", source);
            dataConfig.set("banned." + lower + ".time", System.currentTimeMillis());
        }
        saveDataAsync();
    }

    /**
     * Retrieves stored ban reason directly from in-memory cache (O(1)).
     */
    public String getBanReason(String playerName, String fallbackReason) {
        BanRecord record = bannedCache.get(playerName.toLowerCase());
        if (record != null && record.reason != null && !record.reason.trim().isEmpty()) {
            return record.reason;
        }
        if (fallbackReason != null && !fallbackReason.trim().isEmpty()) {
            return fallbackReason;
        }
        return plugin.getConfig().getString("default-reason", "Struck down by the Ban Hammer");
    }

    /**
     * Retrieves banner's name/source directly from in-memory cache (O(1)).
     */
    public String getBanSource(String playerName, String fallbackSource) {
        BanRecord record = bannedCache.get(playerName.toLowerCase());
        if (record != null && record.source != null && !record.source.trim().isEmpty()) {
            return record.source;
        }
        if (fallbackSource != null && !fallbackSource.trim().isEmpty()) {
            return fallbackSource;
        }
        return "The Ban Hammer";
    }

    /**
     * Formats the disconnect screen displayed on ban and when reconnecting.
     */
    public String formatBanScreen(String playerName, String killer, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            reason = plugin.getConfig().getString("default-reason", "Struck down by the Ban Hammer");
        }
        if (killer == null || killer.trim().isEmpty()) {
            killer = "The Ban Hammer";
        }

        String template = plugin.getConfig().getString(
                "ban-screen",
                "&c&l⚡ The ban hammer has struck you ⚡\n\n" +
                "&7You are permanently banned from this server!\n\n" +
                "&fReason: &e%reason%\n" +
                "&fBanned by: &c%killer%"
        );

        template = template
                .replace("%victim%", playerName)
                .replace("%player%", playerName)
                .replace("%killer%", killer)
                .replace("%reason%", reason);

        return ChatColor.translateAlternateColorCodes('&', template);
    }
}
