package dev.che1s.woodworkingsaw.i18n;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从可编辑的语言文件加载插件文案，缺失时回退到内置文案。 */
public final class LanguageManager {
    private static final Pattern ARGUMENT = Pattern.compile("\\{([a-zA-Z0-9_-]+)}");
    private static final Pattern HEX_COLOR = Pattern.compile("&#([0-9a-fA-F]{6})");

    private final JavaPlugin plugin;
    private volatile String locale = LocaleResolver.DEFAULT_LOCALE;
    private volatile Map<String, String> text = Map.of();

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void saveDefaults() {
        File folder = new File(plugin.getDataFolder(), "lang");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Could not create the language directory.");
            return;
        }
        for (String locale : LocaleResolver.BUNDLED_LOCALES) {
            File file = new File(folder, locale + ".yml");
            if (file.exists()) {
                continue;
            }
            migrateLegacyFile(folder, locale, file);
            if (file.exists()) {
                continue;
            }
            try (InputStream stream = plugin.getResource(bundledResource(locale))) {
                if (stream == null) {
                    continue;
                }
                Files.copy(stream, file.toPath());
            } catch (Exception ignored) {
            }
        }
    }

    public void load(String requested) {
        String target;
        if (LocaleResolver.isAuto(requested)) {
            target = LocaleResolver.detectServerLocale(Locale.getDefault(), System.getenv());
        } else {
            String known = LocaleResolver.knownLocale(requested);
            String custom = LocaleResolver.normalize(requested);
            File customFile = new File(plugin.getDataFolder(), "lang/" + custom + ".yml");
            if (known != null) {
                target = known;
            } else if (LocaleResolver.isSafeCustomName(requested) && customFile.isFile()) {
                target = custom;
            } else {
                plugin.getLogger().warning(
                        "Unsupported language '" + requested + "', falling back to en_us.yml.");
                target = LocaleResolver.DEFAULT_LOCALE;
            }
        }
        Map<String, String> merged = new HashMap<>();
        mergeResource(merged, LocaleResolver.DEFAULT_LOCALE);
        mergeResource(merged, target);
        File file = new File(plugin.getDataFolder(), "lang/" + target + ".yml");
        if (file.exists()) {
            YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
            mergeConfiguration(merged, disk);
        }
        this.locale = target;
        this.text = Map.copyOf(merged);
    }

    public String locale() {
        return locale;
    }

    public String text(String key, String... arguments) {
        return format(text.getOrDefault(key, key), arguments);
    }

    public String message(String key, String... arguments) {
        String[] values = new String[arguments.length + 2];
        System.arraycopy(arguments, 0, values, 0, arguments.length);
        values[arguments.length] = "prefix";
        values[arguments.length + 1] = text.getOrDefault("messages.prefix", "");
        return translateColors(format(text.getOrDefault(key, key), values));
    }

    public String actionBar(String key, String... arguments) {
        return translateColors(format(text.getOrDefault(key, key), arguments));
    }

    public static String translateColors(String text) {
        Matcher matcher = HEX_COLOR.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            StringBuilder replacement = new StringBuilder("\u00a7x");
            for (char c : matcher.group(1).toCharArray()) {
                replacement.append('\u00a7').append(c);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    private static String format(String template, String... arguments) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i + 1 < arguments.length; i += 2) {
            values.put(arguments[i], arguments[i + 1]);
        }
        Matcher matcher = ARGUMENT.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    values.containsKey(matcher.group(1)) ? Objects.toString(values.get(matcher.group(1)), "") : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void mergeResource(Map<String, String> merged, String locale) {
        YamlConfiguration bundled = readResource(bundledResource(locale));
        if (bundled != null) {
            mergeConfiguration(merged, bundled);
        }
    }

    private static void mergeConfiguration(Map<String, String> merged, YamlConfiguration source) {
        for (String key : source.getKeys(true)) {
            if (source.isString(key)) {
                merged.put(key, source.getString(key));
            }
        }
    }

    private YamlConfiguration readResource(String name) {
        try (InputStream stream = plugin.getResource(name)) {
            if (stream == null) {
                return null;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return yaml;
        } catch (Exception error) {
            throw new IllegalStateException("Failed to load bundled resource: " + name, error);
        }
    }

    private static String bundledResource(String locale) {
        return switch (locale) {
            case "en_us" -> "lang/en_US.yml";
            case "zh_cn" -> "lang/zh_CN.yml";
            default -> "lang/" + locale + ".yml";
        };
    }

    private static void migrateLegacyFile(File folder, String locale, File target) {
        String legacy = switch (locale) {
            case "en_us" -> "en_US.yml";
            case "zh_cn" -> "zh_CN.yml";
            default -> null;
        };
        if (legacy == null) {
            return;
        }
        File source = new File(folder, legacy);
        if (!source.isFile()) {
            return;
        }
        try {
            Files.copy(source.toPath(), target.toPath());
        } catch (Exception ignored) {
        }
    }
}
