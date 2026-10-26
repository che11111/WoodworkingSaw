package dev.che1s.woodworkingsaw.metrics;

import dev.che1s.woodworkingsaw.WoodworkingSawPlugin;

public final class MetricsService {
    public static final int SERVICE_ID = 34959;

    private final WoodworkingSawPlugin plugin;
    private RegionMetrics reporter;

    public MetricsService(WoodworkingSawPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void reload() {
        if (!plugin.getConfig().getBoolean("bstats.enabled", true)) {
            shutdown();
            return;
        }
        if (reporter != null) {
            return;
        }
        try {
            reporter = new RegionMetrics(plugin, SERVICE_ID);
        } catch (RuntimeException error) {
            plugin.getLogger().warning(plugin.language().text("logs.metrics-failed", "error", error.toString()));
        }
    }

    public synchronized void shutdown() {
        if (reporter != null) {
            reporter.shutdown();
        }
        reporter = null;
    }
}
