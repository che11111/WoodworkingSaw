package dev.che1s.woodworkingsaw;

import dev.che1s.woodworkingsaw.i18n.LanguageManager;
import dev.che1s.woodworkingsaw.metrics.MetricsService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class WoodworkingSawPlugin extends JavaPlugin {
    static final String CUSTOM_ITEM_ID = "woodworkingsaw:saw_table";
    static final String CUSTOM_BLOCK_ID = "woodworkingsaw:saw_table";

    private LanguageManager language;
    private TransformService transformService;
    private CraftEngineBridge craftEngine;
    private MetricsService metrics;
    private Path importedPackPath;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("transformations.yml", false);
        this.language = new LanguageManager(this);
        language.saveDefaults();
        language.load(getConfig().getString("language", "zh_CN"));
        this.transformService = new TransformService(this, language);
        this.metrics = new MetricsService(this);
        metrics.reload();
        initialiseCraftEngine();
        SawTableCommand command = new SawTableCommand(this, transformService, language);
        if (getCommand("sawtable") != null) {
            getCommand("sawtable").setExecutor(command);
            getCommand("sawtable").setTabCompleter(command);
        }
        getLogger().info(language.text("logs.enabled", "version", getDescription().getVersion(), "language", language.locale()));
    }

    @Override
    public void onDisable() {
        if (metrics != null) {
            metrics.shutdown();
        }
    }

    private void initialiseCraftEngine() {
        try {
            this.craftEngine = CraftEngineBridge.create(this);
            craftEngine.registerInteractionListener(transformService);
            if (getConfig().getBoolean("craftengine.auto-install", true)) {
                ResourceInstaller installer = new ResourceInstaller(this);
                ResourceInstaller.InstallResult result = installer.install();
                this.importedPackPath = result.packDirectory();
                if (result.changed()) {
                    notifyResourceUploaded(result.packDirectory());
                } else if (!result.differences().isEmpty()) {
                    notifyResourceDiff(result.differences());
                } else {
                    getLogger().info(language.text("logs.resource-up-to-date"));
                }
            }
        } catch (IOException | ReflectiveOperationException | LinkageError exception) {
            getLogger().severe(language.text("logs.init-failed", "error", String.valueOf(exception.getMessage())));
            exception.printStackTrace();
            this.craftEngine = null;
        }
    }

    void reloadEverything() throws IOException, ReflectiveOperationException {
        reloadConfig();
        language.load(getConfig().getString("language", "zh_CN"));
        transformService.reloadSettings();
        metrics.reload();
        if (!getConfig().getBoolean("craftengine.auto-install", true)) {
            return;
        }
        ResourceInstaller.InstallResult result = new ResourceInstaller(this).install();
        this.importedPackPath = result.packDirectory();
        if (result.changed()) {
            notifyResourceUploaded(result.packDirectory());
        } else if (!result.differences().isEmpty()) {
            notifyResourceDiff(result.differences());
        }
    }

    private void notifyResourceUploaded(Path packDirectory) {
        notifyAdmins("logs.resource-uploaded", "messages.resource-uploaded", "path", packDirectory.toString());
    }

    private void notifyResourceDiff(List<String> differences) {
        notifyAdmins("logs.resource-diff", "messages.resource-diff", "files", String.join(", ", differences));
    }

    private void notifyAdmins(String logKey, String messageKey, String argName, String argValue) {
        getLogger().info(language.text(logKey, argName, argValue));
        String message = language.message(messageKey, argName, argValue);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("woodworkingsaw.admin")) {
                player.sendMessage(message);
            }
        }
    }

    TransformService transformService() {
        return transformService;
    }

    CraftEngineBridge craftEngine() {
        return craftEngine;
    }

    Path importedPackPath() {
        return importedPackPath;
    }

    public LanguageManager language() {
        return language;
    }
}
