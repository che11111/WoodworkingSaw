package dev.che1s.woodworkingsaw.update;

import dev.che1s.woodworkingsaw.WoodworkingSawPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 每个启动或重载周期最多自动检查一次；自动检查只在控制台提示。 */
public final class UpdateChecker {
    private static final long AUTOMATIC_CHECK_DELAY_SECONDS = 5;
    private static final long MANUAL_COOLDOWN_MILLIS = 30_000;
    private final WoodworkingSawPlugin plugin;
    private final GitHubReleaseClient client = new GitHubReleaseClient();
    private final String installedVersion;
    private volatile CheckRun active;

    public UpdateChecker(WoodworkingSawPlugin plugin) {
        this.plugin = plugin;
        this.installedVersion = plugin.getDescription().getVersion();
    }

    /** 启动或重载后安排一次异步检查，并取消上一周期尚未执行的检查。 */
    public synchronized void reload() {
        shutdown();
        if (!plugin.getConfig().getBoolean("check-for-updates", true)) {
            return;
        }
        CheckRun run = new CheckRun();
        active = run;
        try {
            run.pending = Bukkit.getAsyncScheduler().runDelayed(
                    plugin, ignored -> check(run, null),
                    AUTOMATIC_CHECK_DELAY_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException error) {
            active = null;
            throw error;
        }
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
        cancelPending(run);
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
        if (run != null) {
            cancelPending(run);
        }
    }

    private void cancelPending(CheckRun run) {
        ScheduledTask pending = run.pending;
        run.pending = null;
        if (pending != null) {
            pending.cancel();
        }
    }

    private void check(CheckRun run, CommandSender requester) {
        run.pending = null;
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

    /** 在全局区域线程调用；自动检查只写控制台，管理员命令只回复命令发起者。 */
    private void accept(CheckRun run,
                        GitHubReleaseClient.Release release,
                        CommandSender requester) {
        run.failureReported = false;
        if (requester != null) {
            if (release == null) {
                send(requester, "messages.update-no-new");
            } else {
                sendAvailable(requester, release);
            }
            return;
        }
        if (release == null) {
            return;
        }
        plugin.getLogger().info(plugin.language().text("logs.update-available",
                "latest", release.version(),
                "current", installedVersion,
                "url", release.url()));
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

    private static final class CheckRun {
        private final AtomicBoolean checking = new AtomicBoolean();
        private final AtomicLong lastManual = new AtomicLong();
        private volatile ScheduledTask pending;
        private boolean failureReported;
    }
}
