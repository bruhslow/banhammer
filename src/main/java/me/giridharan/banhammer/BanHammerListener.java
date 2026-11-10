package me.giridharan.banhammer;

import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance BanHammerListener:
 * - Microsecond event returns via preliminary Material type checks.
 * - Zero reflection or NBT parsing during normal non-hammer combat and clicks.
 * - Automatic self-cleaning memory tracking to prevent leaks.
 */
public class BanHammerListener implements Listener {

    private final BanHammerPlugin plugin;
    private final Map<UUID, Long> lastBanHammerHit = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastEquipTime = new ConcurrentHashMap<>();
    private final Particle explosionParticle;

    public BanHammerListener(BanHammerPlugin plugin) {
        this.plugin = plugin;
        this.explosionParticle = resolveExplosionParticle();
    }

    private Particle resolveExplosionParticle() {
        // Modern 1.20.5+ / 26.x
        try {
            return Particle.valueOf("EXPLOSION_EMITTER");
        } catch (Throwable ignored) {}
        // 1.13 - 1.20.4
        try {
            return Particle.valueOf("EXPLOSION_HUGE");
        } catch (Throwable ignored) {}
        try {
            return Particle.valueOf("EXPLOSION");
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Cleans up tracking maps when players quit to ensure zero memory leaks.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastBanHammerHit.remove(uuid);
        lastEquipTime.remove(uuid);
    }

    /**
     * Filters out Bukkit's auto-generated /banhammer:banhammer from client autocomplete
     * so players only see /banhammer.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandSend(PlayerCommandSendEvent event) {
        event.getCommands().removeIf(cmd -> cmd.toLowerCase().startsWith("banhammer:"));
    }

    /**
     * Triggered when player equips the Ban Hammer by switching hotbar slot.
     * Plays ONLY the thunder sound (no chat messages).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Material mat = plugin.getHammerManager().getHammerMaterial();
        Player player = event.getPlayer();
        ItemStack newItem = player.getInventory().getItem(event.getNewSlot());
        ItemStack oldItem = player.getInventory().getItem(event.getPreviousSlot());

        // Fast-path: exit immediately if neither item is the hammer material
        if ((newItem == null || newItem.getType() != mat) && (oldItem == null || oldItem.getType() != mat)) {
            return;
        }

        if (plugin.getHammerManager().isBanHammer(newItem) && !plugin.getHammerManager().isBanHammer(oldItem)) {
            triggerEquipSound(player);
        }
    }

    /**
     * Triggered when player swaps the Ban Hammer into main hand via 'F'.
     * Plays ONLY the thunder sound (no chat messages).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Material mat = plugin.getHammerManager().getHammerMaterial();
        if (event.getMainHandItem().getType() != mat && event.getOffHandItem().getType() != mat) {
            return;
        }

        Player player = event.getPlayer();
        if (plugin.getHammerManager().isBanHammer(event.getMainHandItem()) &&
            !plugin.getHammerManager().isBanHammer(event.getOffHandItem())) {
            triggerEquipSound(player);
        }
    }

    /**
     * Plays thunder sound when equipping the Ban Hammer.
     * No chat messages are sent.
     */
    public void triggerEquipSound(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastEquipTime.get(player.getUniqueId());
        // Cooldown prevents sound spamming on rapid scroll
        if (last != null && (now - last < 1500L)) {
            return;
        }
        lastEquipTime.put(player.getUniqueId(), now);

        try {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
        } catch (Throwable ignored) {}
    }

    /**
     * Prevents dropping the Ban Hammer from hand via 'Q' or inventory throw.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item.getType() != plugin.getHammerManager().getHammerMaterial()) {
            return;
        }

        if (plugin.getHammerManager().isBanHammer(item)) {
            event.setCancelled(true);
            try {
                event.getItemDrop().remove();
            } catch (Throwable ignored) {}
            event.getPlayer().sendMessage(ChatColor.RED + "❌ The Ban Hammer is bound to you and cannot be dropped!");
            event.getPlayer().updateInventory();
        }
    }

    /**
     * Absolute security guard: prevents ANY Ban Hammer item entity from spawning
     * as a dropped item in the world under any circumstance.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack item = event.getEntity().getItemStack();
        if (item.getType() != plugin.getHammerManager().getHammerMaterial()) {
            return;
        }
        if (plugin.getHammerManager().isBanHammer(item)) {
            event.setCancelled(true);
        }
    }

    /**
     * Checks if inventory is an external storage container.
     */
    private boolean isStorageContainer(InventoryType type) {
        if (type == null) return false;
        return type == InventoryType.CHEST
                || type == InventoryType.ENDER_CHEST
                || type == InventoryType.BARREL
                || type == InventoryType.SHULKER_BOX
                || type == InventoryType.DISPENSER
                || type == InventoryType.DROPPER
                || type == InventoryType.HOPPER;
    }

    /**
     * Handles inventory management with ultra-fast preliminary material checks:
     * - Allows players to freely move the hammer between hotbar and main inventory.
     * - Blocks dropping inside GUI (pressing Q on slot or with cursor, clicking outside, etc.).
     * - Blocks storing into external containers (chests, barrels, hoppers, shulkers).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Material hammerMat = plugin.getHammerManager().getHammerMaterial();
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        int hotbarBtn = event.getHotbarButton();
        ItemStack hotbarItem = (hotbarBtn >= 0 && hotbarBtn < 9) ? player.getInventory().getItem(hotbarBtn) : null;

        // Ultra-fast preliminary check: if none of the involved items match the hammer material, exit in nanoseconds!
        boolean hasPotentialHammer = (current != null && current.getType() == hammerMat)
                || (cursor != null && cursor.getType() == hammerMat)
                || (hotbarItem != null && hotbarItem.getType() == hammerMat);

        if (!hasPotentialHammer) {
            return;
        }

        boolean isDropAction = event.getAction() == InventoryAction.DROP_ALL_CURSOR
                || event.getAction() == InventoryAction.DROP_ONE_CURSOR
                || event.getAction() == InventoryAction.DROP_ALL_SLOT
                || event.getAction() == InventoryAction.DROP_ONE_SLOT;

        boolean isDropClick = event.getClick() == ClickType.DROP
                || event.getClick() == ClickType.CONTROL_DROP;

        boolean isOutside = event.getSlotType() == InventoryType.SlotType.OUTSIDE
                || event.getRawSlot() == -999;

        // Catch ANY attempt to drop the hammer from inside an open inventory
        if (isDropAction || isDropClick || isOutside) {
            if (plugin.getHammerManager().isBanHammer(current)
                    || plugin.getHammerManager().isBanHammer(cursor)
                    || plugin.getHammerManager().isBanHammer(hotbarItem)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "❌ The Ban Hammer cannot be dropped!");
                player.updateInventory();
                return;
            }
        }

        // Block storing into external storage containers (chests, barrels, etc.)
        Inventory topInv = event.getView().getTopInventory();
        if (topInv != null && isStorageContainer(topInv.getType())) {

            // Shift-clicking into container
            if (event.isShiftClick() && plugin.getHammerManager().isBanHammer(current)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "❌ The Ban Hammer cannot be stored in containers!");
                player.updateInventory();
                return;
            }

            // Placing into container slot
            if (event.getClickedInventory() != null && event.getClickedInventory().equals(topInv)) {
                if (plugin.getHammerManager().isBanHammer(cursor)) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "❌ The Ban Hammer cannot be stored in containers!");
                    player.updateInventory();
                    return;
                }

                // Number key swap into container slot
                if (event.getClick() == ClickType.NUMBER_KEY && plugin.getHammerManager().isBanHammer(hotbarItem)) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "❌ The Ban Hammer cannot be stored in containers!");
                    player.updateInventory();
                    return;
                }
            }
        }

        // Players can freely organize and move the hammer inside their own inventory and hotbar!
    }

    /**
     * Prevents dragging the Ban Hammer into external containers.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        ItemStack oldCursor = event.getOldCursor();
        if (oldCursor == null || oldCursor.getType() != plugin.getHammerManager().getHammerMaterial()) {
            return;
        }
        if (!plugin.getHammerManager().isBanHammer(oldCursor)) {
            return;
        }

        Inventory topInv = event.getView().getTopInventory();
        if (topInv != null && isStorageContainer(topInv.getType())) {
            int topSize = topInv.getSize();
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < topSize) {
                    event.setCancelled(true);
                    if (event.getWhoClicked() instanceof Player player) {
                        player.sendMessage(ChatColor.RED + "❌ The Ban Hammer cannot be stored in containers!");
                    }
                    return;
                }
            }
        }
    }

    /**
     * Prevents placing the Ban Hammer onto item frames or armor stands.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Material mat = plugin.getHammerManager().getHammerMaterial();
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();

        if (main.getType() != mat && off.getType() != mat) {
            return;
        }

        if (plugin.getHammerManager().isBanHammer(main) || plugin.getHammerManager().isBanHammer(off)) {
            if (event.getRightClicked() instanceof ItemFrame || event.getRightClicked() instanceof ArmorStand) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "❌ The Ban Hammer cannot be placed in item frames or armor stands!");
            }
        }
    }

    /**
     * Checks if an attack is a critical hit (falling during jump)
     * or a Mace smash attack from high above.
     */
    private boolean isCriticalOrSmashHit(Player attacker) {
        // 1. Check fall distance (accumulated when falling from height or from jump apex)
        if (attacker.getFallDistance() > 0.0F) {
            return true;
        }

        // 2. Check if player is airborne and moving downwards
        if (!attacker.isOnGround() && attacker.getVelocity().getY() < -0.05) {
            return true;
        }

        return false;
    }

    /**
     * Ultra-fast check on player damage.
     * Material check exits in nanoseconds for all non-hammer hits.
     * One-shots only on critical hits (jump crits) or smash attacks from high above.
     * Normal spam hits deal normal combat damage.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon.getType() != plugin.getHammerManager().getHammerMaterial()) {
            return;
        }

        if (plugin.getHammerManager().isBanHammer(weapon)) {
            lastBanHammerHit.put(victim.getUniqueId(), System.currentTimeMillis());

            String mode = plugin.getConfig().getString("one-shot-mode", "CRIT_OR_HEIGHT").toUpperCase();
            if (plugin.getConfig().isSet("insta-kill") && plugin.getConfig().getBoolean("insta-kill")) {
                mode = "ALWAYS";
            }

            boolean shouldOneShot;
            if (mode.equals("ALWAYS")) {
                shouldOneShot = true;
            } else if (mode.equals("NEVER")) {
                shouldOneShot = false;
            } else {
                // CRIT_OR_HEIGHT: Only critical hits or high smash attacks one-shot kill!
                shouldOneShot = isCriticalOrSmashHit(attacker);
            }

            if (shouldOneShot) {
                // Fatal one-shot strike!
                event.setDamage(99999.0);

                try {
                    victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 25, 0.4, 0.5, 0.4, 0.15);
                } catch (Throwable ignored) {}

                try {
                    victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.9f);
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Bypasses and negates Totems of Undying across all Minecraft versions.
     * Automatically self-cleans stale hits.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTotemResurrect(EntityResurrectEvent event) {
        if (lastBanHammerHit.isEmpty()) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        Long hitTime = lastBanHammerHit.get(victim.getUniqueId());
        if (hitTime != null) {
            long diff = System.currentTimeMillis() - hitTime;
            if (diff <= 2500L) {
                // Cancel totem resurrection! Player dies normally.
                event.setCancelled(true);
            } else {
                lastBanHammerHit.remove(victim.getUniqueId());
            }
        }
    }

    /**
     * Triggered on player death.
     * The Ban Hammer automatically disappears from drops so mortal players cannot obtain it.
     * All normal inventory items drop and splash naturally.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        // When a player having the Ban Hammer dies, the hammer disappears completely from drops!
        boolean hadBanHammer = event.getDrops().removeIf(item -> plugin.getHammerManager().isBanHammer(item));
        if (hadBanHammer) {
            victim.sendMessage(ChatColor.GRAY + "⚡ Your Ban Hammer vanished upon death.");
        }

        Player killer = victim.getKiller();
        boolean killedByBanHammer = false;
        String killerName = "The Ban Hammer";

        if (killer != null && plugin.getHammerManager().isBanHammer(killer.getInventory().getItemInMainHand())) {
            killedByBanHammer = true;
            killerName = killer.getName();
        } else {
            Long hitTime = lastBanHammerHit.get(victim.getUniqueId());
            if (hitTime != null && (System.currentTimeMillis() - hitTime <= 2500L)) {
                killedByBanHammer = true;
                if (killer != null) {
                    killerName = killer.getName();
                }
            }
        }

        if (!killedByBanHammer) return;

        lastBanHammerHit.remove(victim.getUniqueId());

        final String finalKillerName = killerName;
        final String victimName = victim.getName();

        // Retrieve custom reason (or default) and consume it
        final String banReason = plugin.getReasonManager().consumePendingReason(victimName);

        // 1. General Chat: ONLY ONE single message
        String announceTemplate = plugin.getConfig().getString(
                "announcement-message",
                "&c&l⚡ Here comes the ban hammer, &e&l%victim%&c&l! ⚡"
        ).replace("%victim%", victimName)
         .replace("%killer%", finalKillerName)
         .replace("%reason%", banReason);

        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', announceTemplate));

        // 2. Clear vanilla death message completely so there are no duplicate messages
        event.setDeathMessage(null);

        // 3. For the victim who gets struck: "The ban hammer has struck you"
        victim.sendMessage(ChatColor.translateAlternateColorCodes(
                '&',
                plugin.getConfig().getString("victim-message", "&cThe ban hammer has struck you")
        ));

        // 4. For the person who banned him: "<player> has been banned"
        Player killerPlayer = Bukkit.getPlayer(finalKillerName);
        if (killerPlayer != null && killerPlayer.isOnline() && !killerPlayer.getUniqueId().equals(victim.getUniqueId())) {
            killerPlayer.sendMessage(ChatColor.translateAlternateColorCodes(
                    '&',
                    plugin.getConfig().getString("killer-message", "&a✔ &e%victim% &ahas been banned")
                            .replace("%victim%", victimName)
            ));
        }

        // 5. Fatal strike / death commands (configured in dependencies.yml)
        Location deathLoc = victim.getLocation();
        UUID killerUuid = killer != null ? killer.getUniqueId() : null;
        plugin.getDependencyManager().executeDeathCommands(victimName, victim.getUniqueId(), finalKillerName, killerUuid, banReason, deathLoc);

        // 6. Lightning sequence and execution
        executeLightningAndBan(victim.getUniqueId(), victimName, finalKillerName, banReason, deathLoc);
    }

    /**
     * Executes the series of lightning strikes and then enforces the ban.
     */
    public void executeLightningAndBan(UUID victimUuid, String victimName, String killerName, String reason, Location loc) {
        World world = loc != null ? loc.getWorld() : null;
        final int totalStrikes = Math.max(1, plugin.getConfig().getInt("lightning-strikes-count", 5));
        final int strikeIntervalTicks = Math.max(1, plugin.getConfig().getInt("strike-interval-ticks", 4));

        new BukkitRunnable() {
            int strikesDone = 0;

            @Override
            public void run() {
                if (world != null && strikesDone < totalStrikes) {
                    // Strike visual lightning without burning items
                    world.strikeLightningEffect(loc);

                    if (explosionParticle != null) {
                        try {
                            world.spawnParticle(explosionParticle, loc, 1);
                        } catch (Throwable ignored) {}
                    }

                    try {
                        world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 0.8f);
                    } catch (Throwable ignored) {}

                    strikesDone++;
                } else {
                    cancel();
                    executeBan(victimUuid, victimName, killerName, reason, loc);
                }
            }
        }.runTaskTimer(plugin, 0L, (long) strikeIntervalTicks);
    }

    private void executeBan(UUID victimUuid, String victimName, String killerName, String reason, Location loc) {
        Player killerPlayer = Bukkit.getPlayer(killerName);
        UUID killerUuid = killerPlayer != null ? killerPlayer.getUniqueId() : null;

        // 1. External ban plugins integration: dispatch commands from dependencies.yml
        plugin.getDependencyManager().executeExternalBans(victimName, victimUuid, killerName, killerUuid, reason, loc);

        // 2. Native Bukkit ban list update (if enabled in dependencies.yml)
        if (plugin.getDependencyManager().keepNativeBan()) {
            plugin.getReasonManager().updateBanReason(victimName, reason, killerName);
        }

        // 3. Format clean ban screen & kick victim if still online
        String banScreen = plugin.getReasonManager().formatBanScreen(victimName, killerName, reason);
        Player victim = Bukkit.getPlayer(victimUuid);
        if (victim != null && victim.isOnline()) {
            victim.kickPlayer(banScreen);
        }
    }

    /**
     * Intercepts connection when banned player tries to rejoin (Network Thread).
     * Instantaneous in-memory lookup via ReasonManager (O(1)).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        String name = event.getName();
        BanEntry entry = Bukkit.getBanList(BanList.Type.NAME).getBanEntry(name);
        if (entry != null) {
            String rawReason = entry.getReason();
            String reason = plugin.getReasonManager().getBanReason(name, rawReason);
            String killer = plugin.getReasonManager().getBanSource(name, entry.getSource());
            String screen = plugin.getReasonManager().formatBanScreen(name, killer, reason);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen);
        }
    }

    /**
     * Intercepts login when banned player tries to rejoin (Main Thread Fallback).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (event.getResult() == PlayerLoginEvent.Result.KICK_BANNED) {
            String name = event.getPlayer().getName();
            BanEntry entry = Bukkit.getBanList(BanList.Type.NAME).getBanEntry(name);
            String rawReason = entry != null ? entry.getReason() : null;
            String rawSource = entry != null ? entry.getSource() : null;
            String reason = plugin.getReasonManager().getBanReason(name, rawReason);
            String killer = plugin.getReasonManager().getBanSource(name, rawSource);
            String screen = plugin.getReasonManager().formatBanScreen(name, killer, reason);
            event.setKickMessage(screen);
        }
    }
}
