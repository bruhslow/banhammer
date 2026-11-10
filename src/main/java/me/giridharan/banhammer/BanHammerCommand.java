package me.giridharan.banhammer;

import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BanHammerCommand implements CommandExecutor, TabCompleter {

    private final BanHammerPlugin plugin;

    public BanHammerCommand(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("banhammer.use")) {
            sender.sendMessage(ChatColor.RED + "❌ You do not have permission to use the Ban Hammer.");
            return true;
        }

        // /banhammer (no args) -> gives the Ban Hammer to the player
        if (args.length == 0) {
            if (sender instanceof Player player) {
                giveHammer(player, sender);
            } else {
                sender.sendMessage(ChatColor.RED + "Console usage: /banhammer give <player>");
            }
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "reason" -> {
                return handleReason(sender, args);
            }
            case "give" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /banhammer give <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(ChatColor.RED + "❌ Player '" + args[1] + "' not found or offline.");
                    return true;
                }
                giveHammer(target, sender);
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("banhammer.admin")) {
                    sender.sendMessage(ChatColor.RED + "❌ You do not have permission to reload BanHammer.");
                    return true;
                }
                plugin.reloadConfig();
                plugin.getReasonManager().loadData();
                plugin.getDependencyManager().load();
                plugin.getDependencyManager().checkDependencies();
                sender.sendMessage(ChatColor.GREEN + "✔ BanHammer configs, reasons, and dependencies reloaded successfully!");
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&c&l⚡ BanHammer Usage ⚡\n" +
                        "&e/banhammer &7- Receive the Ban Hammer\n" +
                        "&e/banhammer give <player> &7- Give the Ban Hammer to another player\n" +
                        "&e/banhammer reason <player> <reason...> &7- Set or update the ban reason"
                ));
                return true;
            }
        }
    }

    private boolean handleReason(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /banhammer reason <player> <reason...>");
            return true;
        }

        String targetName = args[1];

        if (args.length == 2) {
            // Check existing reason or pending reason
            BanEntry entry = Bukkit.getBanList(BanList.Type.NAME).getBanEntry(targetName);
            String pending = plugin.getReasonManager().getPendingReason(targetName);

            if (entry != null) {
                String curReason = plugin.getReasonManager().getBanReason(targetName, entry.getReason());
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&e" + targetName + " &cis currently banned. Reason: &f\"" + curReason + "\""
                ));
            } else if (pending != null) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&e" + targetName + " &7has a pending Ban Hammer reason: &f\"" + pending + "\""
                ));
            } else {
                sender.sendMessage(ChatColor.RED + "No reason set for " + targetName + ". Usage: /banhammer reason " + targetName + " <reason...>");
            }
            return true;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));

        // If player is already banned: update ban reason in Bukkit ban list & reasons.yml
        BanEntry banEntry = Bukkit.getBanList(BanList.Type.NAME).getBanEntry(targetName);
        if (banEntry != null) {
            plugin.getReasonManager().updateBanReason(targetName, reason, sender.getName());
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&a✔ Updated ban reason for banned player &e" + targetName + "&a to: &f\"" + reason + "\""
            ));
            sender.sendMessage(ChatColor.GRAY + "When they attempt to rejoin, this reason will be displayed on their screen.");
        } else {
            // Player is not yet banned: save pending reason for when struck by Ban Hammer
            plugin.getReasonManager().setPendingReason(targetName, reason);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&a✔ Set Ban Hammer reason for &e" + targetName + "&a: &f\"" + reason + "\""
            ));
            sender.sendMessage(ChatColor.GRAY + "When you strike them down with the Ban Hammer, they will be banished with this reason!");
        }

        return true;
    }

    private void giveHammer(Player target, CommandSender sender) {
        if (target.getInventory().firstEmpty() == -1) {
            target.sendMessage(ChatColor.RED + "❌ Your inventory is full!");
            if (!target.equals(sender)) {
                sender.sendMessage(ChatColor.RED + "❌ " + target.getName() + "'s inventory is full!");
            }
            return;
        }

        ItemStack hammer = plugin.getHammerManager().createBanHammer();
        target.getInventory().addItem(hammer);

        target.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&c&l⚡ &6You have received the legendary &c&lBan Hammer&6! Strike down any mortal to seal their fate."
        ));

        try {
            target.playSound(target.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.2f);
        } catch (Throwable ignored) {}

        if (!target.equals(sender)) {
            sender.sendMessage(ChatColor.GREEN + "✔ Granted Ban Hammer to " + target.getName());
        }

        // Trigger custom hammer given commands (from dependencies.yml)
        plugin.getDependencyManager().executeHammerGiveCommands(target, sender);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        // Tab completion on first argument: ONLY "give" and "reason"
        if (args.length == 1) {
            String token = args[0].toLowerCase();
            if ("give".startsWith(token)) completions.add("give");
            if ("reason".startsWith(token)) completions.add("reason");
            return completions;
        }

        // Tab completion on second argument: player names
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("reason")) {
                String token = args[1].toLowerCase();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(token)) {
                        completions.add(p.getName());
                    }
                }
            }
            return completions;
        }

        // Tab completion on third argument for reason: suggest <reason>
        if (args.length == 3 && args[0].equalsIgnoreCase("reason")) {
            return Collections.singletonList("<reason>");
        }

        return Collections.emptyList();
    }
}
