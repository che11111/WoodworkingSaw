package dev.che1s.woodworkingsaw.update;

import dev.che1s.woodworkingsaw.WoodworkingSawPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 网络请求始终异步执行；玩家通知通过各玩家自己的调度器发送。 */
public final class UpdateChecker implements Listener {
    private static final long MANUAL_COOLDOWN_MILLIS = 30_000;
    private final WoodworkingSawPlugin plugin;
    private final GitHubReleaseClient client = new GitHubReleaseClient();
    private final String installedVersion;
    private volatile CheckRun active;

    public UpdateChecker(WoodworkingSawPlugin plugin) {
        this.plugin = plugin;
        this.installedVersion = plugin.getDescription().getVersion();
    }

    /** 重载时保留正在运行的计时器和已获取的版本缓存。 */
    public synchronized void reload() {
        if (!plugin.getConfig().getBoolean("check-for-updates", true)) {
            shutdown();
            return;
        }
        if (active != null) {
            return;
        }
        CheckRun run = new CheckRun();
        active = run;
        try {
            run.timer = Bukkit.getAsyncScheduler().runAtFixedRate(
                    plugin, ignored -> check(run, null),
                    5, 6 * 60 * 60, TimeUnit.SECONDS);
        } catch (RuntimeException error) {
            active = null;
            throw error;
        }
        plugin.getLogger().info(plugin.language().text("logs.update-check-enabled"));
    }

    /** 由管理员命令触发一次非阻塞检查。 */
    public void request(CommandSender sender) {
        CheckRun run = active;
        if (run == null) {
            send(sender, "messages.update-disabled");
            return;
        }
        long now = System.currentTimeMillis();
        long previous = run.lastManual.get();
        if (now - previous < MANUAL_COOLDOWN_MILLIS) {
            send(sender, "messages.update-wait");
            return;
        }
        if (!run.checking.compareAndSet(false, true)) {
            send(sender, "messages.update-wait");
            return;
        }
        run.lastManual.set(now);
        send(sender, "messages.update-checking");
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, ignored -> performCheck(run, sender));
        } catch (RejectedExecutionException | IllegalPluginAccessException ignored) {
            run.checking.set(false);
            send(sender, "messages.update-failed");
        }
    }

    public synchronized void shutdown() {
        CheckRun run = active;
        active = null;
        if (run != null && run.timer != null) {
            run.timer.cancel();
        }
    }

    private void check(CheckRun run, CommandSender requester) {
        if (active != run || !run.checking.compareAndSet(false, true)) {
            if (requester != null) {
                send(requester, "messages.update-wait");
            }
            return;
        }
        performCheck(run, requester);
    }

    private void performCheck(CheckRun run, CommandSender requester) {
        if (active != run) {
            run.checking.set(false);
            return;
        }
        try {
            GitHubReleaseClient.Release release = client.fetchLatest()
                    .filter(latest -> ReleaseVersion.isNewer(latest.version(), installedVersion))
                    .orElse(null);
            complete(run, () -> accept(run, release, requester));
        } catch (IOException | RuntimeException error) {
            complete(run, () -> {
                if (!run.failureReported) {
                    run.failureReported = true;
                    plugin.getLogger().warning(plugin.language().text(
                            "logs.update-check-failed", "error", error.toString()));
                }
                if (requester != null) {
                    send(requester, "messages.update-failed");
                }
            });
        } finally {
            run.checking.set(false);
        }
    }

    private void complete(CheckRun run, Runnable action) {
        if (active != run) {
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> {
                if (active == run) {
                    action.run();
                }
            });
        } catch (IllegalPluginAccessException ignored) {
            // 插件关闭可能发生在网络请求返回之后。
        }
    }

    /** 在全局区域线程调用；只对新发现的版本通知在线管理员。 */
    private void accept(CheckRun run,
                        GitHubReleaseClient.Release release,
                        CommandSender requester) {
        run.failureReported = false;
        GitHubReleaseClient.Release previous = run.available;
        run.available = release;
        if (requester != null) {
            if (release == null) {
                send(requester, "messages.update-no-new");
            } else {
                sendAvailable(requester, release);
            }
        }
        if (release == null
                || (previous != null && previous.version().equals(release.version()))) {
            return;
        }
        plugin.getLogger().info(plugin.language().text("logs.update-available",
                "latest", release.version(),
                "current", installedVersion,
                "url", release.url()));
        for (Player player : Bukkit.getOnlinePlayers()) {
            schedulePlayer(player, () -> notifyPlayer(player, run, release));
        }
    }

    private void notifyPlayer(Player player,
                              CheckRun run,
                              GitHubReleaseClient.Release release) {
        if (active != run
                || run.available != release
                || !player.hasPermission("woodworkingsaw.admin")) {
            return;
        }
        if (release.version().equals(
                run.notified.put(player.getUniqueId(), release.version()))) {
            return;
        }
        sendAvailable(player, release);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        CheckRun run = active;
        if (run == null || run.available == null) {
            return;
        }
        Player player = event.getPlayer();
        schedulePlayerLater(player, () -> {
            GitHubReleaseClient.Release release = run.available;
            if (release != null) {
                notifyPlayer(player, run, release);
            }
        }, 60);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        CheckRun run = active;
        if (run != null) {
            run.notified.remove(event.getPlayer().getUniqueId());
        }
    }

    private void send(CommandSender sender, String key, String... arguments) {
        dispatch(sender, () -> sender.sendMessage(plugin.language().message(key, arguments)));
    }

    private void sendAvailable(CommandSender sender, GitHubReleaseClient.Release release) {
        dispatch(sender, () -> {
            String message = plugin.language().message("messages.update-available",
                    "latest", release.version(),
                    "current", installedVersion,
                    "url", release.url());
            Component component = LegacyComponentSerializer.legacySection()
                    .deserialize(message)
                    .clickEvent(ClickEvent.openUrl(release.url()));
            sender.sendMessage(component);
        });
    }

    private void dispatch(CommandSender sender, Runnable action) {
        if (sender instanceof Player player) {
            schedulePlayer(player, action);
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> action.run());
        } catch (IllegalPluginAccessException ignored) {
            // 插件正在关闭。
        }
    }

    private void schedulePlayer(Player player, Runnable action) {
        try {
            player.getScheduler().run(plugin, ignored -> action.run(), () -> {
            });
        } catch (IllegalPluginAccessException ignored) {
            // 插件正在关闭或玩家已经离线。
        }
    }

    private void schedulePlayerLater(Player player, Runnable action, long ticks) {
        try {
            player.getScheduler().runDelayed(plugin, ignored -> action.run(), () -> {
            }, ticks);
        } catch (IllegalPluginAccessException ignored) {
            // 插件正在关闭或玩家已经离线。
        }
    }

    private static final class CheckRun {
        private final AtomicBoolean checking = new AtomicBoolean();
        private final AtomicLong lastManual = new AtomicLong();
        private final Map<UUID, String> notified = new ConcurrentHashMap<>();
        private volatile GitHubReleaseClient.Release available;
        private ScheduledTask timer;
        private boolean failureReported;
    }
}
