package me.giridharan.banhammer;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class HammerManager {

    private final BanHammerPlugin plugin;
    private final NamespacedKey banHammerKey;
    private final Material hammerMaterial;

    public HammerManager(BanHammerPlugin plugin) {
        this.plugin = plugin;
        this.banHammerKey = new NamespacedKey(plugin, "is_ban_hammer");

        // Dynamically resolve weapon material for any version:
        // Uses MACE on 1.21+ / 26.x, falls back to NETHERITE_AXE on 1.16-1.20, or DIAMOND_AXE on older
        Material resolved = Material.getMaterial("MACE");
        if (resolved == null) {
            resolved = Material.getMaterial("NETHERITE_AXE");
        }
        if (resolved == null) {
            resolved = Material.DIAMOND_AXE;
        }
        this.hammerMaterial = resolved;
    }

    public ItemStack createBanHammer() {
        ItemStack item = new ItemStack(hammerMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String nameTemplate = plugin.getConfig().getString("hammer-name", "&c&l⚡ Ban Hammer ⚡");
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', nameTemplate));

            List<String> lore = new ArrayList<>();
            List<String> configLore = plugin.getConfig().getStringList("hammer-lore");
            if (configLore.isEmpty()) {
                lore.add(ChatColor.translateAlternateColorCodes('&', "&7Made for RGX"));
            } else {
                for (String line : configLore) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', line));
                }
            }
            meta.setLore(lore);

            meta.setUnbreakable(true);
            try {
                meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            } catch (Throwable ignored) {}

            // Universal persistent data tag
            meta.getPersistentDataContainer().set(banHammerKey, PersistentDataType.BYTE, (byte) 1);

            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Ultra-fast check:
     * 1. Checks item nullability.
     * 2. Checks material enum equality.
     * 3. Checks hasItemMeta() to prevent expensive CraftMetaItem cloning.
     * 4. Checks PDC byte flag.
     */
    public boolean isBanHammer(ItemStack item) {
        if (item == null || item.getType() != hammerMaterial || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte tag = meta.getPersistentDataContainer().get(banHammerKey, PersistentDataType.BYTE);
        return tag != null && tag == 1;
    }

    public NamespacedKey getBanHammerKey() {
        return banHammerKey;
    }

    public Material getHammerMaterial() {
        return hammerMaterial;
    }
}
