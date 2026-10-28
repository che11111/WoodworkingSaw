package dev.che1s.woodworkingsaw;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class ResourceInstaller {
    private static final String RESOURCE_VERSION = "1.0.17";
    private static final List<String> MANAGED_FILES = List.of(
            "pack.yml",
            "configuration/blocks/saw_table.yml",
            "configuration/categories.yml",
            "configuration/recipes/saw_table.yml",
            "configuration/recipes/saw_table_cutting.yml",
            "configuration/langs/zh_cn.yml",
            "configuration/langs/en_us.yml",
            "resourcepack/assets/woodworkingsaw/models/block/simple_woodworking_table.json",
            "resourcepack/assets/woodworkingsaw/models/item/saw_table.json",
            "resourcepack/assets/woodworkingsaw/textures/block/stonecutter_saw.png",
            "resourcepack/assets/woodworkingsaw/textures/block/stonecutter_saw.png.mcmeta",
            "resourcepack/assets/woodworkingsaw/textures/block/simple_woodworking_table.png"
    );

    private final WoodworkingSawPlugin plugin;

    ResourceInstaller(WoodworkingSawPlugin plugin) {
        this.plugin = plugin;
    }

    InstallResult install() throws IOException {
        Path ceDirectory = plugin.getDataFolder().toPath()
                .getParent()
                .resolve("CraftEngine")
                .resolve("resources")
                .resolve("WoodworkingSaw");
        Path backupDirectory = plugin.getDataFolder().toPath().resolve("resources");
        Path marker = ceDirectory.resolve(".woodworkingsaw-version");
        boolean versionChanged = !Files.isRegularFile(marker)
                || !RESOURCE_VERSION.equals(Files.readString(marker).trim());

        // 备份目录始终与 JAR 内置内容一致，供用户参考/恢复。
        for (String relative : MANAGED_FILES) {
            copyEmbedded("craftengine/" + relative, backupDirectory.resolve(relative));
        }

        // CE 目录：版本变化时整体覆盖；否则只补齐缺失文件，保留用户在 CE 目录里的修改。
        boolean changed = versionChanged;
        for (String relative : MANAGED_FILES) {
            Path target = ceDirectory.resolve(relative);
            if (versionChanged) {
                changed |= copyEmbedded("craftengine/" + relative, target);
            } else {
                changed |= copyEmbeddedIfMissing("craftengine/" + relative, target);
            }
        }

        if (changed) {
            Files.writeString(marker, RESOURCE_VERSION + System.lineSeparator());
        }

        List<String> differences = diff(ceDirectory, backupDirectory);
        return new InstallResult(ceDirectory, changed, differences);
    }

    private boolean copyEmbeddedIfMissing(String resourceName, Path target) throws IOException {
        if (Files.isRegularFile(target)) {
            return false;
        }
        return copyEmbedded(resourceName, target);
    }

    private boolean copyEmbedded(String resourceName, Path target) throws IOException {
        try (InputStream input = plugin.getResource(resourceName)) {
            if (input == null) {
                throw new IOException("JAR 中缺少资源：" + resourceName);
            }
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (Files.isRegularFile(target)
                    && Files.mismatch(target, temporary) == -1L) {
                Files.deleteIfExists(temporary);
                return false;
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        }
    }

    private List<String> diff(Path ceDirectory, Path backupDirectory) throws IOException {
        List<String> differences = new ArrayList<>();
        for (String relative : MANAGED_FILES) {
            Path ceFile = ceDirectory.resolve(relative);
            Path backupFile = backupDirectory.resolve(relative);
            boolean ceExists = Files.isRegularFile(ceFile);
            boolean backupExists = Files.isRegularFile(backupFile);
            if (ceExists != backupExists || (ceExists && Files.mismatch(ceFile, backupFile) != -1L)) {
                differences.add(relative);
            }
        }
        return differences;
    }

    record InstallResult(Path packDirectory, boolean changed, List<String> differences) {
    }
}
