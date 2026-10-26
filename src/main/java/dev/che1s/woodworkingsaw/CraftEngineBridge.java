package dev.che1s.woodworkingsaw;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 通过反射访问 CraftEngine
 */
final class CraftEngineBridge implements Listener {
    private static final String CUSTOM_EVENT =
            "net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent";
    private static final String BLOCKS_API =
            "net.momirealms.craftengine.bukkit.api.CraftEngineBlocks";
    private static final String ITEMS_API =
            "net.momirealms.craftengine.bukkit.api.CraftEngineItems";

    private final WoodworkingSawPlugin plugin;
    private final Method blockActionMethod;
    private final Method customBlockMethod;
    private final Method blockIdMethod;
    private final Method eventHandMethod;
    private final Method eventItemMethod;
    private final Method eventBukkitBlockMethod;
    private final Method eventCancelMethod;
    private final Method loadedBlocksMethod;
    private final Method itemByIdMethod;
    private final Method buildItemMethod;

    private CraftEngineBridge(
            WoodworkingSawPlugin plugin,
            Method blockActionMethod,
            Method customBlockMethod,
            Method blockIdMethod,
            Method eventHandMethod,
            Method eventItemMethod,
            Method eventBukkitBlockMethod,
            Method eventCancelMethod,
            Method loadedBlocksMethod,
            Method itemByIdMethod,
            Method buildItemMethod
    ) {
        this.plugin = plugin;
        this.blockActionMethod = blockActionMethod;
        this.customBlockMethod = customBlockMethod;
        this.blockIdMethod = blockIdMethod;
        this.eventHandMethod = eventHandMethod;
        this.eventItemMethod = eventItemMethod;
        this.eventBukkitBlockMethod = eventBukkitBlockMethod;
        this.eventCancelMethod = eventCancelMethod;
        this.loadedBlocksMethod = loadedBlocksMethod;
        this.itemByIdMethod = itemByIdMethod;
        this.buildItemMethod = buildItemMethod;
    }

    static CraftEngineBridge create(WoodworkingSawPlugin plugin)
            throws ReflectiveOperationException {
        Class<?> eventClass = Class.forName(CUSTOM_EVENT, true, plugin.getClass().getClassLoader());
        Class<?> blocksClass = Class.forName(BLOCKS_API, true, plugin.getClass().getClassLoader());
        Class<?> itemsClass = Class.forName(ITEMS_API, true, plugin.getClass().getClassLoader());
        Class<?> blockDefinitionClass = Class.forName(
                "net.momirealms.craftengine.core.block.BlockDefinition",
                false,
                plugin.getClass().getClassLoader()
        );
        Class<?> itemDefinitionClass = Class.forName(
                "net.momirealms.craftengine.bukkit.item.BukkitItemDefinition",
                false,
                plugin.getClass().getClassLoader()
        );

        return new CraftEngineBridge(
                plugin,
                eventClass.getMethod("action"),
                eventClass.getMethod("customBlock"),
                blockDefinitionClass.getMethod("id"),
                eventClass.getMethod("hand"),
                eventClass.getMethod("item"),
                eventClass.getMethod("bukkitBlock"),
                eventClass.getMethod("setCancelled", boolean.class),
                blocksClass.getMethod("loadedBlocks"),
                itemsClass.getMethod("byId", String.class),
                itemDefinitionClass.getMethod("buildBukkitItem")
        );
    }

    @SuppressWarnings("unchecked")
    void registerInteractionListener(TransformService transformService) throws ReflectiveOperationException {
        Class<?> eventClass = Class.forName(
                CUSTOM_EVENT,
                true,
                plugin.getClass().getClassLoader()
        );
        EventExecutor executor = (listener, event) -> handleInteraction(event, transformService);
        Bukkit.getPluginManager().registerEvent(
                (Class<? extends Event>) eventClass,
                this,
                EventPriority.HIGHEST,
                executor,
                plugin,
                true
        );
        plugin.getLogger().info("已连接 CraftEngine 自定义方块事件。");
    }

    private void handleInteraction(Event event, TransformService transformService) {
        try {
            Object action = blockActionMethod.invoke(event);
            if (action == null) {
                return;
            }
            String actionName = action.toString();
            boolean leftClick = "LEFT_CLICK".equals(actionName);
            boolean rightClick = "RIGHT_CLICK".equals(actionName);
            if (!leftClick && !rightClick) {
                return;
            }
            Object definition = customBlockMethod.invoke(event);
            Object id = blockIdMethod.invoke(definition);
            if (id == null || !WoodworkingSawPlugin.CUSTOM_BLOCK_ID.equals(id.toString())) {
                return;
            }

            Player player = ((org.bukkit.event.player.PlayerEvent) event).getPlayer();

            if (leftClick) {
                ItemStack mainHand = player.getInventory().getItemInMainHand();
                if (mainHand == null || mainHand.getType().isAir()) {
                    openCuttingGui(player, event);
                    eventCancelMethod.invoke(event, true);
                }
                return;
            }

            Object hand = eventHandMethod.invoke(event);
            boolean offHand = hand != null && "OFF_HAND".equals(hand.toString());
            Object rawItem = eventItemMethod.invoke(event);
            ItemStack held = offHand
                    ? player.getInventory().getItemInOffHand()
                    : player.getInventory().getItemInMainHand();
            if ((held == null || held.getType().isAir()) && rawItem instanceof ItemStack stack) {
                held = stack;
            }
            if (transformService.handle(player, held)) {
                eventCancelMethod.invoke(event, true);
            }
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("处理 CraftEngine 锯木台交互失败：" + exception.getMessage());
        }
    }

    private void openCuttingGui(Player player, Event event) throws ReflectiveOperationException {
        Object bukkitBlock = eventBukkitBlockMethod.invoke(event);
        if (bukkitBlock instanceof Block block) {
            player.openStonecutter(block.getLocation(), true);
        }
    }

    boolean isDefinitionLoaded() {
        try {
            Object value = loadedBlocksMethod.invoke(null);
            if (!(value instanceof Map<?, ?> blocks)) {
                return false;
            }
            return blocks.keySet().stream()
                    .anyMatch(key -> WoodworkingSawPlugin.CUSTOM_BLOCK_ID.equals(key.toString()));
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    ItemStack buildCustomItem() {
        try {
            Object definition = itemByIdMethod.invoke(null, WoodworkingSawPlugin.CUSTOM_ITEM_ID);
            if (definition == null) {
                return null;
            }
            Object stack = buildItemMethod.invoke(definition);
            return stack instanceof ItemStack item ? item : null;
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("无法构建 CraftEngine 锯木台物品：" + exception.getMessage());
            return null;
        }
    }
}
