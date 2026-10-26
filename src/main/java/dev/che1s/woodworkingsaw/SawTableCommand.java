package dev.che1s.woodworkingsaw;

import dev.che1s.woodworkingsaw.i18n.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class SawTableCommand implements CommandExecutor, TabCompleter {
    private final WoodworkingSawPlugin plugin;
    private final TransformService transformService;
    private final LanguageManager language;

    SawTableCommand(WoodworkingSawPlugin plugin, TransformService transformService, LanguageManager language) {
        this.plugin = plugin;
        this.transformService = transformService;
        this.language = language;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, args);
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            default -> help(sender, label);
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("woodworkingsaw.give")) {
            sender.sendMessage(language.message("messages.no-permission-give"));
            return;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(language.message("messages.player-not-found", "player", args[1]));
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(language.message("messages.console-usage"));
            return;
        }

        int amount = 1;
        int amountIndex = args.length >= 2 ? 2 : 1;
        if (args.length > amountIndex) {
            try {
                amount = Integer.parseInt(args[amountIndex]);
            } catch (NumberFormatException ignored) {
                amount = 0;
            }
            if (amount < 1 || amount > 64) {
                sender.sendMessage(language.message("messages.invalid-amount"));
                return;
            }
        }

        ItemStack item = plugin.craftEngine().buildCustomItem();
        if (item == null) {
            sender.sendMessage(language.message("messages.item-not-loaded"));
            return;
        }
        item.setAmount(amount);
        target.getInventory().addItem(item).values().forEach(leftover ->
                target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        sender.sendMessage(language.message("messages.give-success", "player", target.getName(), "amount", Integer.toString(amount)));
    }

    private void status(CommandSender sender) {
        if (!sender.hasPermission("woodworkingsaw.admin")) {
            sender.sendMessage(language.message("messages.no-permission-status"));
            return;
        }
        boolean customLoaded = plugin.craftEngine().isDefinitionLoaded();
        sender.sendMessage(language.message(customLoaded ? "messages.status-block-loaded" : "messages.status-block-not-loaded"));
        if (plugin.importedPackPath() != null) {
            sender.sendMessage(language.message("messages.status-resource-dir", "path", plugin.importedPackPath().toString()));
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("woodworkingsaw.admin")) {
            sender.sendMessage(language.message("messages.no-permission-reload"));
            return;
        }
        try {
            plugin.reloadEverything();
            sender.sendMessage(language.message("messages.reload-success"));
        } catch (Exception exception) {
            sender.sendMessage(language.message("messages.reload-failed", "error", exception.getMessage()));
            plugin.getLogger().warning("Reload failed: " + exception.getMessage());
        }
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(language.message("messages.help-usage"));
        sender.sendMessage(language.message("messages.help-give", "label", label));
        sender.sendMessage(language.message("messages.help-status", "label", label));
        sender.sendMessage(language.message("messages.help-reload", "label", label));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("give", "status", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filter(Arrays.asList("1", "8", "16", "32", "64"), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                result.add(value);
            }
        }
        return result;
    }
}
