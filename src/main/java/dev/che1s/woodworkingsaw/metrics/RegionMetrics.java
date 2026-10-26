package dev.che1s.woodworkingsaw.metrics;

import dev.che1s.woodworkingsaw.WoodworkingSawPlugin;
import org.bstats.MetricsBase;
import org.bstats.json.JsonObjectBuilder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

/** bStats 上报器，在全局区域线程（Paper/Folia）上采集平台数据。 */
final class RegionMetrics {
    private final MetricsBase base;

    RegionMetrics(WoodworkingSawPlugin plugin, int serviceId) {
        File file = new File(plugin.getDataFolder().getParentFile(), "bStats/config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!config.isSet("serverUuid")) {
            config.addDefault("enabled", true);
            config.addDefault("serverUuid", UUID.randomUUID().toString());
            config.addDefault("logFailedRequests", false);
            config.addDefault("logSentData", false);
            config.addDefault("logResponseStatusText", false);
            config.options().copyDefaults(true).header(
                    "bStats anonymous metrics / bStats 匿名统计\n"
                            + "Set enabled to false to disable globally. / enabled: false 全局关闭统计。\n"
                            + "https://bstats.org/");
            try {
                config.save(file);
            } catch (IOException error) {
                plugin.getLogger().warning("Unable to save bStats config: " + error.getMessage());
            }
        }
        this.base = new MetricsBase("bukkit", config.getString("serverUuid"), serviceId,
                config.getBoolean("enabled", true),
                RegionMetrics::platformData,
                data -> data.appendField("pluginVersion", plugin.getDescription().getVersion()),
                action -> Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> action.run()),
                plugin::isEnabled,
                (message, error) -> plugin.getLogger().log(Level.WARNING, message, error),
                plugin.getLogger()::info,
                config.getBoolean("logFailedRequests", false),
                config.getBoolean("logSentData", false),
                config.getBoolean("logResponseStatusText", false));
    }

    private static void platformData(JsonObjectBuilder data) {
        data.appendField("playerAmount", Bukkit.getOnlinePlayers().size())
                .appendField("onlineMode", Bukkit.getOnlineMode() ? 1 : 0)
                .appendField("bukkitVersion", Bukkit.getVersion())
                .appendField("bukkitName", Bukkit.getName())
                .appendField("javaVersion", System.getProperty("java.version"))
                .appendField("osName", System.getProperty("os.name"))
                .appendField("osArch", System.getProperty("os.arch"))
                .appendField("osVersion", System.getProperty("os.version"))
                .appendField("coreCount", Runtime.getRuntime().availableProcessors());
    }

    void shutdown() {
        this.base.shutdown();
    }
}
