package dev.che1s.woodworkingsaw;

import dev.che1s.woodworkingsaw.i18n.LanguageManager;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TransformService {
    private static final long DUPLICATE_WINDOW_NANOS = 120_000_000L;

    private static final class Rule {
        final Material target;
        final int inputAmount;
        final int outputAmount;

        Rule(Material target, int inputAmount, int outputAmount) {
            this.target = target;
            this.inputAmount = inputAmount;
            this.outputAmount = outputAmount;
        }
    }

    private final WoodworkingSawPlugin plugin;
    private final LanguageManager language;
    private volatile Map<Material, Rule> transformations = Map.of();
    private final Map<UUID, Long> recentActions = new ConcurrentHashMap<>();

    private boolean sneakAll;
    private boolean sound;
    private boolean actionBar;

    TransformService(WoodworkingSawPlugin plugin, LanguageManager language) {
        this.plugin = plugin;
        this.language = language;
        reloadSettings();
    }

    void reloadSettings() {
        sneakAll = plugin.getConfig().getBoolean("transform.sneak-all", true);
        sound = plugin.getConfig().getBoolean("feedback.sound", true);
        actionBar = plugin.getConfig().getBoolean("feedback.action-bar", true);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(transformFile());
        this.transformations = buildTransformMap(yaml.getList("rules"));
    }

    private File transformFile() {
        return new File(plugin.getDataFolder(), "transformations.yml");
    }

    private Map<Material, Rule> buildTransformMap(List<?> rules) {
        if (rules == null) {
            return Map.of();
        }
        Map<Material, Rule> result = new LinkedHashMap<>();
        for (Object entry : rules) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Material source = Material.matchMaterial(String.valueOf(map.get("input")));
            Material target = Material.matchMaterial(String.valueOf(map.get("output")));
            if (source == null || target == null) {
                continue;
            }
            int inputAmount = parseAmount(String.valueOf(map.get("input-amount")), 1);
            int outputAmount = parseAmount(String.valueOf(map.get("output-amount")), 1);
            result.putIfAbsent(source, new Rule(target, inputAmount, outputAmount));
        }
        return Map.copyOf(result);
    }

    static int parseAmount(String configuredValue, int fallback) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return fallback;
        }
        try {
            int amount = Integer.parseInt(configuredValue.trim());
            return amount > 0 ? amount : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    boolean handle(Player player, ItemStack held) {
        if (!player.hasPermission("woodworkingsaw.use")
                || held == null
                || held.getType().isAir()
                || held.getAmount() <= 0) {
            return false;
        }
        Rule rule = transformations.get(held.getType());
        if (rule == null) {
            return false;
        }
        if (isDuplicate(player)) {
            return false;
        }

        int available = held.getAmount();
        if (available < rule.inputAmount) {
            return false;
        }
        int units = sneakAll && player.isSneaking() ? available / rule.inputAmount : 1;
        int consumed = units * rule.inputAmount;
        int produced = units * rule.outputAmount;

        if (consumed >= available) {
            held.setType(rule.target);
            held.setAmount(produced);
        } else {
            held.setAmount(available - consumed);
            ItemStack result = held.clone();
            result.setType(rule.target);
            result.setAmount(produced);
            player.getInventory().addItem(result).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }

        if (sound) {
            player.getWorld().playSound(
                    player.getLocation(),
                    "minecraft:ui.stonecutter.take_result",
                    SoundCategory.BLOCKS,
                    0.75F,
                    1.15F
            );
        }
        if (actionBar) {
            player.sendActionBar(language.actionBar("messages.action-bar", "count", Integer.toString(produced)));
        }
        return true;
    }

    private boolean isDuplicate(Player player) {
        long now = System.nanoTime();
        long previous = recentActions.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < DUPLICATE_WINDOW_NANOS) {
            return true;
        }
        recentActions.put(player.getUniqueId(), now);
        return false;
    }
}
