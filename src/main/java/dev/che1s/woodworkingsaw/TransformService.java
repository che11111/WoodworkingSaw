package dev.che1s.woodworkingsaw;

import dev.che1s.woodworkingsaw.i18n.LanguageManager;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TransformService {
    private static final long DUPLICATE_WINDOW_NANOS = 120_000_000L;

    record Rule(Material source, Material target, int inputAmount, int outputAmount) {
    }

    record RuleBuild(Map<Material, Rule> rules, List<String> unavailableItems) {
    }

    private final WoodworkingSawPlugin plugin;
    private final LanguageManager language;
    private volatile Map<Material, Rule> transformations = Map.of();
    private final Map<UUID, Long> recentActions = new ConcurrentHashMap<>();

    private volatile boolean sneakAll;
    private volatile boolean sound;
    private volatile boolean actionBar;

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
        RuleBuild build = buildTransformRules(yaml.getList("rules"));
        this.transformations = build.rules();
        if (!build.unavailableItems().isEmpty()) {
            List<String> examples = build.unavailableItems().subList(
                    0, Math.min(8, build.unavailableItems().size()));
            String suffix = build.unavailableItems().size() > examples.size() ? ", ..." : "";
            plugin.getLogger().info(language.text(
                    "logs.transform-version-skipped",
                    "count", Integer.toString(build.unavailableItems().size()),
                    "items", String.join(", ", examples) + suffix));
        }
    }

    private File transformFile() {
        return new File(plugin.getDataFolder(), "transformations.yml");
    }

    static Map<Material, Rule> buildTransformMap(List<?> rules) {
        return buildTransformRules(rules).rules();
    }

    static RuleBuild buildTransformRules(List<?> rules) {
        if (rules == null) {
            return new RuleBuild(Map.of(), List.of());
        }
        Map<Material, Rule> result = new LinkedHashMap<>();
        Set<String> unavailableItems = new LinkedHashSet<>();
        for (Object entry : rules) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            String inputId = identifier(map.get("input"));
            String outputId = identifier(map.get("output"));
            Material source = matchMaterial(inputId);
            Material target = matchMaterial(outputId);
            if (source == null || target == null) {
                if (source == null) {
                    unavailableItems.add(inputId);
                }
                if (target == null) {
                    unavailableItems.add(outputId);
                }
                continue;
            }
            int inputAmount = parseAmount(String.valueOf(map.get("input-amount")), 1);
            int outputAmount = parseAmount(String.valueOf(map.get("output-amount")), 1);
            result.putIfAbsent(source, new Rule(source, target, inputAmount, outputAmount));
        }
        return new RuleBuild(
                Collections.unmodifiableMap(result),
                List.copyOf(unavailableItems));
    }

    private static String identifier(Object configured) {
        if (configured == null) {
            return "<missing>";
        }
        String value = String.valueOf(configured).trim();
        return value.isEmpty() ? "<missing>" : value;
    }

    private static Material matchMaterial(String identifier) {
        if ("<missing>".equals(identifier)) {
            return null;
        }
        return Material.matchMaterial(identifier);
    }

    List<Rule> effectiveRules() {
        return List.copyOf(this.transformations.values());
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
        int available = held.getAmount();
        if (available < rule.inputAmount) {
            return false;
        }
        if (isDuplicate(player)) {
            return false;
        }
        int units = sneakAll && player.isSneaking() ? available / rule.inputAmount : 1;
        int consumed = units * rule.inputAmount;
        int produced = units * rule.outputAmount;

        if (consumed >= available && produced <= rule.target().getMaxStackSize()) {
            held.setType(rule.target);
            held.setAmount(produced);
        } else {
            ItemStack result = held.clone();
            result.setType(rule.target);
            result.setAmount(Math.min(produced, result.getMaxStackSize()));
            int remainingInput = available - consumed;
            if (remainingInput > 0) {
                held.setAmount(remainingInput);
            } else {
                held.setType(Material.AIR);
                held.setAmount(1);
            }
            player.getInventory().addItem(result).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            addOrDropRemainder(player, result, produced - result.getAmount());
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

    private static void addOrDropRemainder(Player player, ItemStack template, int amount) {
        int maxStackSize = Math.max(1, template.getMaxStackSize());
        for (int stackSize : splitOutput(amount, maxStackSize)) {
            ItemStack result = template.clone();
            result.setAmount(stackSize);
            player.getInventory().addItem(result).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    static List<Integer> splitOutput(int amount, int maxStackSize) {
        if (amount <= 0) {
            return List.of();
        }
        int stackLimit = Math.max(1, maxStackSize);
        List<Integer> stacks = new ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, stackLimit);
            stacks.add(stackSize);
            remaining -= stackSize;
        }
        return stacks;
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
